package com.sandeep.awsdocumentapi.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Also thrown when the document exists but belongs to someone else: we never confirm to a caller
 * that an id they don't own is real.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("Document " + id + " not found");
    }
}
