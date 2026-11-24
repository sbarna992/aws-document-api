package com.sandeep.awsdocumentapi.analysis;

/**
 * The provider rejected our request or returned an answer we cannot use (declined, cut off, no tool call,
 * wrong shape). Not the caller's fault; the message is safe to show, the cause goes to the log.
 */
public class AnalysisFailedException extends RuntimeException {

    public AnalysisFailedException(String message) {
        super(message);
    }

    public AnalysisFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
