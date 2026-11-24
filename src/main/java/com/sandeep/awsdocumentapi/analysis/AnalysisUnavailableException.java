package com.sandeep.awsdocumentapi.analysis;

/** Analysis is switched off here, or the provider is temporarily unable to serve: the caller may retry later. */
public class AnalysisUnavailableException extends RuntimeException {

    public AnalysisUnavailableException(String message) {
        super(message);
    }

    public AnalysisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
