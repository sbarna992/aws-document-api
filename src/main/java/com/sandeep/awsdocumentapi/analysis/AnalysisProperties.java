package com.sandeep.awsdocumentapi.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Document analysis settings. There is deliberately no API key here: the Anthropic SDK reads
 * {@code ANTHROPIC_API_KEY} from the environment itself, so the key never passes through Spring.
 *
 * @param enabled   off by default, so the app, the tests and every clone of the repo run without a key or cost
 * @param model     Claude model id
 * @param effort    how hard the model thinks: low, medium, high, xhigh or max
 * @param maxTokens ceiling on the reply, thinking included
 * @param timeout   per attempt; the SDK retries rate limits and overloads twice on top of this
 */
@ConfigurationProperties(prefix = "documents.analysis")
public record AnalysisProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("claude-opus-5-5") String model,
        @DefaultValue("medium") String effort,
        @DefaultValue("16000") long maxTokens,
        @DefaultValue("PT2M") Duration timeout) {
}
