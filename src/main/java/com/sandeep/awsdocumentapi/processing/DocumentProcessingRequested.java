package com.sandeep.awsdocumentapi.processing;

import java.util.UUID;

/**
 * Published (inside the transaction that marks the document PROCESSING) when a client asks for processing.
 * Today it is handled in-process by {@link DocumentProcessor}; on AWS the handler becomes "send to SQS".
 */
public record DocumentProcessingRequested(UUID documentId) {
}
