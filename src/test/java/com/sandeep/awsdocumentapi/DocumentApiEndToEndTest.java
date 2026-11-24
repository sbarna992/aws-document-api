package com.sandeep.awsdocumentapi;

import com.sandeep.awsdocumentapi.analysis.AnalysisFailedException;
import com.sandeep.awsdocumentapi.analysis.AnalysisUnavailableException;
import com.sandeep.awsdocumentapi.analysis.DocumentAnalysis;
import com.sandeep.awsdocumentapi.analysis.DocumentAnalysis.Finding;
import com.sandeep.awsdocumentapi.analysis.DocumentAnalysis.TokenUsage;
import com.sandeep.awsdocumentapi.analysis.DocumentAnalyzer;
import com.sandeep.awsdocumentapi.document.CreateDocumentRequest;
import com.sandeep.awsdocumentapi.document.DocumentAnalysisResponse;
import com.sandeep.awsdocumentapi.document.DocumentCreatedResponse;
import com.sandeep.awsdocumentapi.document.DocumentDetailResponse;
import com.sandeep.awsdocumentapi.document.DocumentRepository;
import com.sandeep.awsdocumentapi.document.DocumentResponse;
import com.sandeep.awsdocumentapi.document.DocumentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * The whole lifecycle over real HTTP, against the local PostgreSQL and a throw-away storage directory.
 * This is the automated version of "run the .http file top to bottom".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class DocumentApiEndToEndTest {

    private static final String OWNER_HEADER = "X-User-Id";

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void useTemporaryStorage(DynamicPropertyRegistry registry) {
        registry.add("documents.storage.local.root", () -> storageRoot.toString());
    }

    @Autowired
    private RestTestClient client;

    @Autowired
    private DocumentRepository repository;

    @MockitoBean
    private DocumentAnalyzer analyzer;

    /** Unique per test run so leftovers from a failed run can never affect the next one. */
    private final String owner = "e2e-" + UUID.randomUUID();

    @AfterEach
    void deleteWhatWeCreated() {
        repository.deleteAll(repository.findByOwnerIdOrderByCreatedAtDesc(owner));
    }

    @Test
    void createUploadProcessDownloadAndDelete() {
        byte[] content = "Hello, document!".getBytes(StandardCharsets.UTF_8);

        // 1. Create metadata; we are handed an upload URL
        DocumentCreatedResponse created = client.post().uri("/documents")
                .header(OWNER_HEADER, owner)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateDocumentRequest("hello.txt", "text/plain"))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists(HttpHeaders.LOCATION)
                .expectBody(DocumentCreatedResponse.class)
                .returnResult().getResponseBody();
        UUID id = created.document().id();
        assertThat(created.document().status()).isEqualTo(DocumentStatus.PENDING_UPLOAD);
        assertThat(created.uploadUrl().getPath()).isEqualTo("/documents/" + id + "/content");

        // 2. No bytes yet: download and processing are both refused
        client.get().uri("/documents/{id}/content", id).header(OWNER_HEADER, owner)
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        client.post().uri("/documents/{id}/process", id).header(OWNER_HEADER, owner)
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        client.post().uri("/documents/{id}/uploaded", id).header(OWNER_HEADER, owner)
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT); // "confirmed" before any bytes: refused

        // 3. Upload to exactly the URL we were given
        client.put().uri(created.uploadUrl())
                .header(OWNER_HEADER, owner)
                .body(content)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentResponse.class)
                .value(response -> {
                    assertThat(response.status()).isEqualTo(DocumentStatus.UPLOADED);
                    assertThat(response.fileSize()).isEqualTo(content.length);
                });
        assertThat(storageRoot).isDirectoryContaining(path -> path.getFileName().toString().startsWith(id.toString()));

        // 3b. Confirm (P2.S2.8): the same client flow as on S3. Locally it is idempotent — the PUT already
        //     marked the row UPLOADED — and the API re-reads the size from storage rather than trusting us.
        client.post().uri("/documents/{id}/uploaded", id).header(OWNER_HEADER, owner)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentDetailResponse.class)
                .value(response -> {
                    assertThat(response.document().status()).isEqualTo(DocumentStatus.UPLOADED);
                    assertThat(response.document().fileSize()).isEqualTo(content.length);
                    assertThat(response.downloadUrl()).isNotNull();
                });

        // 4. Metadata now carries a download URL, and it serves the same bytes back
        DocumentDetailResponse detail = getDocument(id);
        assertThat(detail.downloadUrl()).isNotNull();
        client.get().uri(detail.downloadUrl())
                .header(OWNER_HEADER, owner)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
                .expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, "attachment.*hello\\.txt.*")
                .expectBody(byte[].class).isEqualTo(content);

        // 5. Processing is accepted immediately and completes in the background
        client.post().uri("/documents/{id}/process", id).header(OWNER_HEADER, owner)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(DocumentResponse.class)
                .value(response -> assertThat(response.status()).isEqualTo(DocumentStatus.PROCESSING));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            DocumentResponse processed = getDocument(id).document();
            assertThat(processed.status()).isEqualTo(DocumentStatus.PROCESSED);
            assertThat(processed.checksumSha256()).isEqualTo(sha256Hex(content));
        });

        // 6. Listing is per owner; another owner cannot even confirm the id exists
        client.get().uri("/documents").header(OWNER_HEADER, owner)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<DocumentResponse>>() {
                })
                .value(documents -> assertThat(documents).hasSize(1));
        client.get().uri("/documents/{id}", id).header(OWNER_HEADER, "someone-else")
                .exchange().expectStatus().isNotFound();

        // 7. Delete removes the row and the bytes
        client.delete().uri("/documents/{id}", id).header(OWNER_HEADER, owner)
                .exchange().expectStatus().isNoContent();
        client.get().uri("/documents/{id}", id).header(OWNER_HEADER, owner)
                .exchange().expectStatus().isNotFound();
        assertThat(storageRoot).isEmptyDirectory();
    }

    /** The analysis endpoint over real HTTP. The analyzer is a mock: no key, no network, no cost. */
    @Test
    void analysisReturnsFindingsAndMapsEveryFailureToAProblem() {
        UUID id = createAndUpload("notes.txt", "text/plain", "Renew the contract by 2026-12-31.");
        given(analyzer.analyze(eq("notes.txt"), any(), any())).willReturn(new DocumentAnalysis("claude-opus-5-5",
                "meeting notes", "A reminder to renew a contract.",
                List.of(new Finding("deadline", "Contract renewal due", "Renew the contract by 2026-12-31.", null)),
                new TokenUsage(120, 45)));

        // 1. Success: structured findings, each quoting its evidence, plus the token counts
        client.post().uri("/documents/{id}/analysis", id).header(OWNER_HEADER, owner)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentAnalysisResponse.class)
                .value(response -> {
                    assertThat(response.documentId()).isEqualTo(id);
                    assertThat(response.model()).isEqualTo("claude-opus-5-5");
                    assertThat(response.findings()).singleElement()
                            .satisfies(finding -> assertThat(finding.evidence()).contains("2026-12-31"));
                    assertThat(response.usage().inputTokens()).isEqualTo(120);
                });

        // 2. Someone else's document: 404, and nothing is sent anywhere
        client.post().uri("/documents/{id}/analysis", id).header(OWNER_HEADER, "someone-else")
                .exchange().expectStatus().isNotFound();

        // 3. Claude unusable or unavailable: 502 / 503 problem details, never a stack trace
        willThrow(new AnalysisFailedException("The analysis was cut off before it finished"))
                .given(analyzer).analyze(any(), any(), any());
        client.post().uri("/documents/{id}/analysis", id).header(OWNER_HEADER, owner)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
        willThrow(new AnalysisUnavailableException("Document analysis is not enabled on this server"))
                .given(analyzer).analyze(any(), any(), any());
        client.post().uri("/documents/{id}/analysis", id).header(OWNER_HEADER, owner)
                .exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // 4. A Word document: 415 before the analyzer is ever called
        UUID wordId = createAndUpload("notes.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "not really a docx");
        client.post().uri("/documents/{id}/analysis", wordId).header(OWNER_HEADER, owner)
                .exchange().expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    private UUID createAndUpload(String filename, String contentType, String content) {
        DocumentCreatedResponse created = client.post().uri("/documents")
                .header(OWNER_HEADER, owner)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateDocumentRequest(filename, contentType))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(DocumentCreatedResponse.class)
                .returnResult().getResponseBody();
        client.put().uri(created.uploadUrl())
                .header(OWNER_HEADER, owner)
                .body(content.getBytes(StandardCharsets.UTF_8))
                .exchange()
                .expectStatus().isOk();
        return created.document().id();
    }

    private DocumentDetailResponse getDocument(UUID id) {
        return client.get().uri("/documents/{id}", id).header(OWNER_HEADER, owner)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentDetailResponse.class)
                .returnResult().getResponseBody();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
