package com.sandeep.awsdocumentapi.analysis;

import java.util.List;

/**
 * The result of one analysis: what kind of document it is, a short summary, and findings that each
 * quote the document's own words, so every finding can be checked against the source.
 *
 * @param model the model that produced it, as reported by the provider
 * @param usage tokens billed for the call, so the cost of every analysis is visible
 */
public record DocumentAnalysis(String model, String documentType, String summary, List<Finding> findings,
                               TokenUsage usage) {

    /**
     * @param evidence the exact passage in the document that supports the finding
     * @param page     1-based page number for paged documents (PDF); null for plain text
     */
    public record Finding(String category, String description, String evidence, Integer page) {
    }

    public record TokenUsage(long inputTokens, long outputTokens) {
    }
}
