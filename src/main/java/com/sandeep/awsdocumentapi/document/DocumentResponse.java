package com.sandeep.awsdocumentapi.document;

import java.time.Instant;
import java.util.UUID;

/** Document metadata as exposed by the API. Never leaks the storage key. */
public record DocumentResponse(
        UUID id,
        String filename,
        String contentType,
        Long fileSize,
        DocumentStatus status,
        String checksumSha256,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getFileSize(),
                document.getStatus(),
                document.getChecksumSha256(),
                document.getFailureReason(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
