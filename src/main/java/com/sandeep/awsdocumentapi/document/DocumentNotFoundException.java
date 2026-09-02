package com.sandeep.awsdocumentapi.document;

import java.util.UUID;

/**
 * Also thrown when the document exists but belongs to someone else: we never confirm to a caller
 * that an id they don't own is real.
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("Document " + id + " not found");
    }
}
