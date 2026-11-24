package com.sandeep.awsdocumentapi.analysis;

import org.springframework.http.MediaType;

/**
 * Turns a document's bytes into structured findings. The service depends on this interface, not on
 * Claude: tests use a fake, the feature can be switched off ({@link DisabledDocumentAnalyzer}), and a
 * different provider (for example Claude on Amazon Bedrock) can be plugged in without touching callers.
 */
public interface DocumentAnalyzer {

    /**
     * @param filename    the name the owner gave the document; sent as the document's title
     * @param contentType one of the types {@link #supports(MediaType)} accepts
     * @param content     the document's bytes, exactly as stored
     * @throws AnalysisUnavailableException if analysis is switched off or the provider is temporarily unavailable
     * @throws AnalysisFailedException      if the provider rejected the request or gave an unusable answer
     */
    DocumentAnalysis analyze(String filename, MediaType contentType, byte[] content);

    /** PDF and plain text only: the formats Claude reads natively and that we have designed for. */
    static boolean supports(MediaType contentType) {
        return MediaType.APPLICATION_PDF.equalsTypeAndSubtype(contentType)
                || MediaType.TEXT_PLAIN.equalsTypeAndSubtype(contentType);
    }
}
