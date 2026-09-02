package com.sandeep.awsdocumentapi.processing;

import com.sandeep.awsdocumentapi.document.Document;
import com.sandeep.awsdocumentapi.document.DocumentRepository;
import com.sandeep.awsdocumentapi.document.DocumentStatus;
import com.sandeep.awsdocumentapi.storage.DocumentStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The local stand-in for a queue-driven worker. "Processing" is computing a SHA-256 checksum,
 * which is deliberately trivial: the interesting part is the lifecycle, not the work.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProcessor {

    private static final int MAX_FAILURE_REASON_LENGTH = 1000;

    private final DocumentRepository repository;
    private final DocumentStorage storage;

    /**
     * Runs on a background thread, and only after the transaction that published the event has
     * committed. Without the AFTER_COMMIT guarantee this could read the row before it says PROCESSING.
     */
    @Async
    @TransactionalEventListener
    public void on(DocumentProcessingRequested event) {
        process(event.documentId());
    }

    void process(UUID id) {
        Document document = repository.findById(id).orElse(null);
        if (document == null) {
            log.warn("Processing requested for document {} but it no longer exists", id);
            return;
        }
        if (document.getStatus() != DocumentStatus.PROCESSING) {
            log.warn("Processing requested for document {} but its status is {}; skipping", id, document.getStatus());
            return;
        }

        try {
            String checksum = sha256(storage.load(document.getStorageKey()));
            document.markProcessed(checksum);
            log.info("Processed document {}: sha256={}", id, checksum);
        } catch (Exception e) {
            log.error("Processing failed for document {}", id, e);
            document.markFailed(truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        repository.save(document);
    }

    private static String sha256(Resource resource) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = resource.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String truncate(String reason) {
        return reason.length() <= MAX_FAILURE_REASON_LENGTH ? reason : reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
}
