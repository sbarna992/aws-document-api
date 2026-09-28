package com.sandeep.awsdocumentapi.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.net.URI;
import java.util.Optional;

/**
 * Where document bytes live. The service layer depends only on this; the implementation is
 * the local filesystem ({@code documents.storage.type=local}, the default) or Amazon S3
 * ({@code documents.storage.type=s3}).
 */
public interface DocumentStorage {

    /**
     * Stores the content under the key, replacing any existing object.
     *
     * @return number of bytes written
     */
    long store(String storageKey, InputStream content);

    /**
     * @throws StoredObjectNotFoundException if nothing is stored under the key
     */
    Resource load(String storageKey);

    /** Removes the object if present; a no-op otherwise. */
    void delete(String storageKey);

    boolean exists(String storageKey);

    /**
     * A URL the client can {@code PUT} the bytes to directly, bypassing the API — or empty if this
     * storage cannot hand out such URLs, in which case the API accepts the bytes itself.
     * The content type is pinned: the client must send exactly it.
     */
    Optional<URI> presignedUploadUrl(String storageKey, String contentType);

    /** A URL the client can {@code GET} the bytes from directly — or empty if the API must serve them. */
    Optional<URI> presignedDownloadUrl(String storageKey);
}
