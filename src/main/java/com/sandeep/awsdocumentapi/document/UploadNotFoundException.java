package com.sandeep.awsdocumentapi.document;

import java.util.UUID;

/** The client says the upload finished, but storage has no bytes for the document. */
public class UploadNotFoundException extends RuntimeException {

    public UploadNotFoundException(UUID id) {
        super("Document " + id + " has no uploaded content yet");
    }
}
