package com.sandeep.awsdocumentapi.document;

import java.util.UUID;

/** Raised on the local upload path, where the API receives the bytes and can refuse them outright. */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(UUID id, long size, long max) {
        super("Document " + id + " is " + size + " bytes; the maximum is " + max);
    }
}
