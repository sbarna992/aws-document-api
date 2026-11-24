package com.sandeep.awsdocumentapi.analysis;

import org.springframework.http.MediaType;

/** Registered when {@code documents.analysis.enabled} is not true: the endpoint exists but answers 503. */
class DisabledDocumentAnalyzer implements DocumentAnalyzer {

    @Override
    public DocumentAnalysis analyze(String filename, MediaType contentType, byte[] content) {
        throw new AnalysisUnavailableException("Document analysis is not enabled on this server");
    }
}
