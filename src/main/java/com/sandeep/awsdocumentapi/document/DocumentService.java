package com.sandeep.awsdocumentapi.document;

import com.sandeep.awsdocumentapi.processing.DocumentProcessingRequested;
import com.sandeep.awsdocumentapi.storage.DocumentStorage;
import com.sandeep.awsdocumentapi.storage.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository repository;
    private final DocumentStorage storage;
    private final ApplicationEventPublisher events;

    /** A document's bytes together with the metadata needed to serve them. */
    public record DocumentContent(Document document, Resource resource) {
    }

    @Transactional
    public Document create(String ownerId, CreateDocumentRequest request) {
        UUID id = UUID.randomUUID();
        String storageKey = StorageKeys.forDocument(id, request.filename());
        Document document = repository.save(
                new Document(id, ownerId, request.filename(), request.contentType(), storageKey));
        log.info("Created document {} ({}) for owner {}", id, request.filename(), ownerId);
        return document;
    }

    @Transactional(readOnly = true)
    public Document get(String ownerId, UUID id) {
        return repository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Document> list(String ownerId) {
        return repository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    /**
     * Deliberately not transactional: streaming a large body to storage can take a while and
     * must not hold a database connection while it does. The row is updated afterwards.
     */
    public Document storeContent(String ownerId, UUID id, InputStream content) {
        Document document = get(ownerId, id);
        DocumentStatus status = document.getStatus();
        if (status != DocumentStatus.PENDING_UPLOAD && status != DocumentStatus.UPLOADED) {
            throw new InvalidDocumentStateException(id, status, "accept new content");
        }

        long size = storage.store(document.getStorageKey(), content);
        document.markUploaded(size);
        log.info("Stored {} bytes for document {}", size, id);
        return repository.save(document);
    }

    public DocumentContent loadContent(String ownerId, UUID id) {
        Document document = get(ownerId, id);
        if (document.getStatus() == DocumentStatus.PENDING_UPLOAD) {
            throw new InvalidDocumentStateException(id, document.getStatus(), "be downloaded");
        }
        return new DocumentContent(document, storage.load(document.getStorageKey()));
    }

    /**
     * Flips the document to PROCESSING and announces it. The event is delivered only after this
     * transaction commits (see DocumentProcessor), so the worker can never observe the old state.
     */
    @Transactional
    public Document requestProcessing(String ownerId, UUID id) {
        Document document = get(ownerId, id);
        DocumentStatus status = document.getStatus();
        if (status == DocumentStatus.PENDING_UPLOAD || status == DocumentStatus.PROCESSING) {
            throw new InvalidDocumentStateException(id, status, "be processed");
        }

        document.markProcessing();
        Document saved = repository.save(document);
        events.publishEvent(new DocumentProcessingRequested(id));
        log.info("Processing requested for document {}", id);
        return saved;
    }

    /**
     * Bytes go first: storage delete is idempotent, so if the row delete then fails the
     * caller can simply retry. The other order could leave an unreachable file behind.
     */
    @Transactional
    public void delete(String ownerId, UUID id) {
        Document document = get(ownerId, id);
        storage.delete(document.getStorageKey());
        repository.delete(document);
        log.info("Deleted document {} for owner {}", id, ownerId);
    }
}
