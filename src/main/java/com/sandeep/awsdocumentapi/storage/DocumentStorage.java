package com.sandeep.awsdocumentapi.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * Where document bytes live. The service layer depends only on this; the implementation is
 * the local filesystem today and Amazon S3 once the API is replatformed.
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
}
