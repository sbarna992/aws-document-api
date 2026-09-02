package com.sandeep.awsdocumentapi.storage;

import java.util.UUID;

/**
 * Builds the key a document is stored under: {@code <uuid>-<sanitised filename>}.
 * The UUID guarantees uniqueness; the filename part is only there so a human browsing
 * the directory (or S3 console) can tell what a file is. The real name stays in the database.
 */
public final class StorageKeys {

    private static final int MAX_NAME_LENGTH = 100;

    private StorageKeys() {
    }

    public static String forDocument(UUID id, String originalFilename) {
        return id + "-" + sanitise(originalFilename);
    }

    static String sanitise(String filename) {
        // Strip any directory component a client may have sent (C:\Users\me\proposal.pdf, ../etc/passwd)
        String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        // A name that is only dots would let "." or ".." through
        if (name.isBlank() || name.chars().allMatch(c -> c == '.')) {
            return "file";
        }
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }
}
