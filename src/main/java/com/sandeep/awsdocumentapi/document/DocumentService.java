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
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository repository;
    private final DocumentStorage storage;
    private final ApplicationEventPublisher events;
    private final DocumentLimits limits;

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
     * Where the client should send the bytes, if storage can take them directly (a presigned S3 PUT).
     * Empty means the API accepts them itself. Ownership was checked by whoever loaded the document.
     */
    public Optional<URI> uploadUrl(Document document) {
        return storage.presignedUploadUrl(document.getStorageKey(), document.getContentType());
    }

    /** Where the client can fetch the bytes directly (a presigned S3 GET), or empty if the API serves them. */
    public Optional<URI> downloadUrl(Document document) {
        return storage.presignedDownloadUrl(document.getStorageKey());
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
        long max = limits.maxFileSize().toBytes();
        if (size > max) {
            storage.delete(document.getStorageKey());
            throw new FileTooLargeException(id, size, max);
        }
        document.markUploaded(size);
        log.info("Stored {} bytes for document {}", size, id);
        return repository.save(document);
    }

    /**
     * TEMPORARY (P2.S2.8, removed in Phase 9): the client says "my upload finished". The API does not
     * believe it — it asks storage whether the bytes really exist and how big they are, and only then
     * moves the row on. The client can delay the transition, never fake it. Phase 9 replaces this with
     * an S3 ObjectCreated event that needs no client at all.
     * <p>
     * Size is enforced here, after the fact (presigned PUT cannot make S3 refuse it — ADR 13, C): an
     * oversized object is deleted and the document marked FAILED. On a versioned bucket that delete is a
     * delete marker; the bytes stay billable until the lifecycle rule expires the noncurrent version.
     */
    @Transactional
    public Document confirmUpload(String ownerId, UUID id) {
        Document document = get(ownerId, id);
        DocumentStatus status = document.getStatus();
        if (status != DocumentStatus.PENDING_UPLOAD && status != DocumentStatus.UPLOADED) {
            throw new InvalidDocumentStateException(id, status, "confirm an upload");
        }

        long size = storage.sizeOf(document.getStorageKey())
                .orElseThrow(() -> new UploadNotFoundException(id));
        long max = limits.maxFileSize().toBytes();
        if (size > max) {
            storage.delete(document.getStorageKey());
            document.markRejected("file exceeds " + max + " bytes (was " + size + ")");
            log.warn("Rejected document {}: {} bytes exceeds the {}-byte limit", id, size, max);
        } else {
            document.markUploaded(size);
            log.info("Confirmed upload of {} bytes for document {}", size, id);
        }
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
