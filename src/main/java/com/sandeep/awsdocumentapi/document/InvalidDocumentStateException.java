package com.sandeep.awsdocumentapi.document;

import java.util.UUID;

/** The requested operation is not allowed in the document's current lifecycle state. */
public class InvalidDocumentStateException extends RuntimeException {

    public InvalidDocumentStateException(UUID id, DocumentStatus status, String operation) {
        super("Document " + id + " is " + status + " and cannot " + operation);
    }
}
