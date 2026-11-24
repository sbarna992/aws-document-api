package com.sandeep.awsdocumentapi.document;

import com.sandeep.awsdocumentapi.analysis.DocumentAnalysis;
import com.sandeep.awsdocumentapi.analysis.DocumentAnalysis.Finding;
import com.sandeep.awsdocumentapi.analysis.DocumentAnalysis.TokenUsage;

import java.util.List;
import java.util.UUID;

/** Body of {@code POST /documents/{id}/analysis}. */
public record DocumentAnalysisResponse(UUID documentId, String model, String documentType, String summary,
                                       List<Finding> findings, TokenUsage usage) {

    static DocumentAnalysisResponse from(UUID documentId, DocumentAnalysis analysis) {
        return new DocumentAnalysisResponse(documentId, analysis.model(), analysis.documentType(), analysis.summary(),
                analysis.findings(), analysis.usage());
    }
}
