package com.sandeep.awsdocumentapi.document;

/**
 * Lifecycle of a document.
 * <pre>
 * PENDING_UPLOAD -> UPLOADED -> PROCESSING -> PROCESSED
 *                                          \-> FAILED
 * </pre>
 */
public enum DocumentStatus {
    /** Metadata row exists; client has been handed an upload URL but no bytes have arrived. */
    PENDING_UPLOAD,
    /** Bytes are in storage. */
    UPLOADED,
    /** Asynchronous processing has been requested and is in flight. */
    PROCESSING,
    PROCESSED,
    FAILED
}
