package com.sandeep.awsdocumentapi.document;

import com.sandeep.awsdocumentapi.processing.DocumentProcessingRequested;
import com.sandeep.awsdocumentapi.storage.DocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.unit.DataSize;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private DocumentService service;

    /** Tiny limit so the size tests need no big fixtures. */
    private static final long MAX = 100;

    @BeforeEach
    void setUp() {
        service = new DocumentService(repository, storage, events, new DocumentLimits(DataSize.ofBytes(MAX)));
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
