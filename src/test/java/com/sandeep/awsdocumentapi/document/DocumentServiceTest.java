package com.sandeep.awsdocumentapi.document;

import com.sandeep.awsdocumentapi.processing.DocumentProcessingRequested;
import com.sandeep.awsdocumentapi.storage.DocumentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
    @InjectMocks
    private DocumentService service;

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
