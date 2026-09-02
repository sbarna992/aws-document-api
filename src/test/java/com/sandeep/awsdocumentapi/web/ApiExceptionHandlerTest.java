package com.sandeep.awsdocumentapi.web;

import com.sandeep.awsdocumentapi.document.DocumentController;
import com.sandeep.awsdocumentapi.document.DocumentNotFoundException;
import com.sandeep.awsdocumentapi.document.DocumentService;
import com.sandeep.awsdocumentapi.document.DocumentStatus;
import com.sandeep.awsdocumentapi.document.InvalidDocumentStateException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** Web slice only: the service is mocked, so these tests exercise the HTTP layer and error mapping. */
@WebMvcTest(DocumentController.class)
class ApiExceptionHandlerTest {

    private static final String OWNER = "alice";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private DocumentService service;

    @Test
    void unknownDocumentIsA404Problem() {
        UUID id = UUID.randomUUID();
        given(service.get(OWNER, id)).willThrow(new DocumentNotFoundException(id));

        assertThat(mvc.get().uri("/documents/{id}", id).header("X-User-Id", OWNER))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.title", title -> title.assertThat().isEqualTo("Not Found"))
                .hasPathSatisfying("$.detail", detail -> detail.assertThat().asString().contains(id.toString()))
                .hasPathSatisfying("$.instance", instance -> instance.assertThat().isEqualTo("/documents/" + id));
    }

    @Test
    void wrongLifecycleStateIsA409Problem() {
        UUID id = UUID.randomUUID();
        given(service.loadContent(OWNER, id))
                .willThrow(new InvalidDocumentStateException(id, DocumentStatus.PENDING_UPLOAD, "be downloaded"));

        assertThat(mvc.get().uri("/documents/{id}/content", id).header("X-User-Id", OWNER))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", d -> d.assertThat().asString().contains("PENDING_UPLOAD"));
    }

    @Test
    void invalidBodyListsEveryFieldError() {
        assertThat(mvc.post().uri("/documents")
                .header("X-User-Id", OWNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"filename": "", "contentType": "not a media type"}
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", d -> d.assertThat().isEqualTo("Request validation failed"))
                .hasPathSatisfying("$.errors[*].field",
                        f -> f.assertThat().asArray().containsExactlyInAnyOrder("filename", "contentType"));
    }

    @Test
    void missingOwnerHeaderIsA400Problem() {
        assertThat(mvc.get().uri("/documents"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", d -> d.assertThat().asString().contains("X-User-Id"));
    }

    @Test
    void malformedIdIsA400Problem() {
        assertThat(mvc.get().uri("/documents/not-a-uuid").header("X-User-Id", OWNER))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void unexpectedFailureIsA500ProblemThatLeaksNothing() {
        given(service.list(OWNER)).willThrow(new IllegalStateException("password=hunter2"));

        assertThat(mvc.get().uri("/documents").header("X-User-Id", OWNER))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyText()
                .doesNotContain("hunter2")
                .doesNotContain("IllegalStateException")
                .contains("An unexpected error occurred");
    }
}
