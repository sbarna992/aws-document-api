package com.sandeep.awsdocumentapi.document;

import com.sandeep.awsdocumentapi.analysis.DocumentAnalysis;
import com.sandeep.awsdocumentapi.analysis.DocumentAnalyzer;
import com.sandeep.awsdocumentapi.analysis.UnsupportedDocumentTypeException;
import com.sandeep.awsdocumentapi.processing.DocumentProcessingRequested;
import com.sandeep.awsdocumentapi.storage.DocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final String OWNER = "alice";

    @Mock
    private DocumentRepository repository;
    @Mock
    private DocumentStorage storage;
    @Mock
    private ApplicationEventPublisher events;
    @Mock
    private DocumentAnalyzer analyzer;
    private DocumentService service;

    /** Tiny limit so the size tests need no big fixtures. */
    private static final long MAX = 100;

    @BeforeEach
    void setUp() {
        service = new DocumentService(repository, storage, events, new DocumentLimits(DataSize.ofBytes(MAX)), analyzer);
    }

    @Test
    void requestProcessingMarksProcessingAndPublishesEvent() {
        Document document = uploadedDocument();
        given(repository.findByIdAndOwnerId(document.getId(), OWNER)).willReturn(Optional.of(document));
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.requestProcessing(OWNER, document.getId());

        assertThat(result.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        verify(events).publishEvent(new DocumentProcessingRequested(document.getId()));
    }

    @Test
    void requestProcessingRejectsDocumentsWithoutContent() {
        Document document = newDocument(); // still PENDING_UPLOAD
        given(repository.findByIdAndOwnerId(document.getId(), OWNER)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.requestProcessing(OWNER, document.getId()))
                .isInstanceOf(InvalidDocumentStateException.class);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void requestProcessingRejectsDocumentsAlreadyBeingProcessed() {
        Document document = uploadedDocument();
        document.markProcessing();
        given(repository.findByIdAndOwnerId(document.getId(), OWNER)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.requestProcessing(OWNER, document.getId()))
                .isInstanceOf(InvalidDocumentStateException.class);
        verify(events, never()).publishEvent(any());
    }

    // --- confirmUpload (P2.S2.8): verify with storage, never trust the client ---

    @Test
    void confirmUploadMarksUploadedWithTheSizeStorageReports() {
        Document document = newDocument();
        given(repository.findByIdAndOwnerId(document.getId(), OWNER)).willReturn(Optional.of(document));
        given(storage.sizeOf(document.getStorageKey())).willReturn(Optional.of(42L));
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.confirmUpload(OWNER, document.getId());

        assertThat(result.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(result.getFileSize()).isEqualTo(42L);
    }

    @Test
    void confirmUploadRefusesWhenStorageHasNoBytes() {
        Document document = newDocument();
        given(repository.findByIdAndOwnerId(document.getId(), OWNER)).willReturn(Optional.of(document));
        given(storage.sizeOf(document.getStorageKey())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmUpload(OWNER, document.getId()))
                .isInstanceOf(UploadNotFoundException.class);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PENDING_UPLOAD);
        verify(repository, never()).save(any());
    }

    @Test
    void confirmUploadRejectsAndDeletesAnOversizedObject() {
        Document document = newDocument();
        given(repository.findByIdAndOwnerId(document.getId(), OWNER)).willReturn(Optional.of(document));
        given(storage.sizeOf(document.getStorageKey())).willReturn(Optional.of(MAX + 1));
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.confirmUpload(OWNER, document.getId());

        assertThat(result.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(result.getFailureReason()).startsWith("file exceeds " + MAX + " bytes");
        assertThat(result.getFileSize()).isNull();
        verify(storage).delete(document.getStorageKey());
    }

    @Test
    void confirmUploadRefusesADocumentThatIsBeingProcessed() {
        Document document = uploadedDocument();
        document.markProcessing();
        given(repository.findByIdAndOwnerId(document.getId(), OWNER)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.confirmUpload(OWNER, document.getId()))
                .isInstanceOf(InvalidDocumentStateException.class);
        verify(storage, never()).sizeOf(any());
    }

    // --- analyze (ADR 16): ownership, state and type are checked before any byte leaves the API ---

    @Test
    void analyzeSendsTheStoredBytesToTheAnalyzer() {
        Document document = uploadedDocument();
        byte[] bytes = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        DocumentAnalysis expected = new DocumentAnalysis("claude-opus-5-5", "proposal", "A proposal.", List.of(),
                new DocumentAnalysis.TokenUsage(10, 5));
        given(repository.findByIdAndOwnerId(document.getId(), OWNER)).willReturn(Optional.of(document));
        given(storage.load(document.getStorageKey())).willReturn(new ByteArrayResource(bytes));
        given(analyzer.analyze(eq("proposal.pdf"), eq(MediaType.APPLICATION_PDF), aryEq(bytes))).willReturn(expected);

        assertThat(service.analyze(OWNER, document.getId())).isSameAs(expected);
    }

    @Test
    void analyzeRefusesSomeoneElsesDocument() {
        UUID id = UUID.randomUUID();
        given(repository.findByIdAndOwnerId(id, OWNER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(OWNER, id)).isInstanceOf(DocumentNotFoundException.class);
        verify(analyzer, never()).analyze(any(), any(), any());
    }

    @Test
    void analyzeRefusesADocumentWithoutContent() {
        Document document = newDocument(); // still PENDING_UPLOAD
        given(repository.findByIdAndOwnerId(document.getId(), OWNER)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.analyze(OWNER, document.getId()))
                .isInstanceOf(InvalidDocumentStateException.class);
        verify(storage, never()).load(any());
        verify(analyzer, never()).analyze(any(), any(), any());
    }

    @Test
    void analyzeRefusesUnsupportedTypesWithoutReadingTheBytes() {
        UUID id = UUID.randomUUID();
        Document document = new Document(id, OWNER, "proposal.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", id + "-proposal.docx");
        document.markUploaded(1234);
        given(repository.findByIdAndOwnerId(id, OWNER)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.analyze(OWNER, id)).isInstanceOf(UnsupportedDocumentTypeException.class);
        verify(storage, never()).load(any());
        verify(analyzer, never()).analyze(any(), any(), any());
    }

    private static Document newDocument() {
        UUID id = UUID.randomUUID();
        return new Document(id, OWNER, "proposal.pdf", "application/pdf", id + "-proposal.pdf");
    }

    private static Document uploadedDocument() {
        Document document = newDocument();
        document.markUploaded(1234);
        return document;
    }
}
