package com.sandeep.awsdocumentapi.analysis;

import java.util.UUID;

/** The document is in a format analysis does not accept (anything but PDF or plain text). */
public class UnsupportedDocumentTypeException extends RuntimeException {

    public UnsupportedDocumentTypeException(UUID id, String contentType) {
        super("Document " + id + " is " + contentType + "; analysis supports application/pdf and text/plain");
    }
}
