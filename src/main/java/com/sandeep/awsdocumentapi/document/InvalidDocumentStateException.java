package com.sandeep.awsdocumentapi.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/** The requested operation is not allowed in the document's current lifecycle state. */
@ResponseStatus(HttpStatus.CONFLICT)
public class InvalidDocumentStateException extends RuntimeException {

    public InvalidDocumentStateException(UUID id, DocumentStatus status, String operation) {
        super("Document " + id + " is " + status + " and cannot " + operation);
    }
}
