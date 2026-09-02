package com.sandeep.awsdocumentapi.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class Document implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Column(name = "checksum_sha256")
    private String checksumSha256;

    @Column(name = "failure_reason")
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // We assign the UUID ourselves, so Spring Data cannot infer "new" from "id == null".
    // Without this every save() does a SELECT before the INSERT.
    @Transient
    private boolean isNew = true;

    public Document(UUID id, String ownerId, String originalFilename, String contentType, String storageKey) {
        this.id = id;
        this.ownerId = ownerId;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.storageKey = storageKey;
        this.status = DocumentStatus.PENDING_UPLOAD;
    }

    /** Called once the bytes are confirmed in storage. */
    public void markUploaded(long fileSize) {
        this.fileSize = fileSize;
        this.status = DocumentStatus.UPLOADED;
        this.checksumSha256 = null;
        this.failureReason = null;
    }

    public void markProcessing() {
        this.status = DocumentStatus.PROCESSING;
        this.checksumSha256 = null;
        this.failureReason = null;
    }

    public void markProcessed(String checksumSha256) {
        this.status = DocumentStatus.PROCESSED;
        this.checksumSha256 = checksumSha256;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = DocumentStatus.FAILED;
        this.failureReason = reason;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
