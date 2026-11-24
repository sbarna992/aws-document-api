package com.sandeep.awsdocumentapi.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exactly one {@link DocumentAnalyzer}: Claude when {@code documents.analysis.enabled=true}, otherwise one
 * that refuses with 503. The Anthropic client is created only in the first case, so with analysis off the
 * application needs no key and never opens a connection to the API.
 */
@Configuration
class AnalysisConfig {

    /**
     * Thread-safe, one per application; Spring closes it on shutdown. {@code fromEnv()} reads
     * {@code ANTHROPIC_API_KEY} — the key never appears in configuration files or logs.
     */
    @Bean
    @ConditionalOnProperty(name = "documents.analysis.enabled", havingValue = "true")
    AnthropicClient anthropicClient(AnalysisProperties properties) {
        return AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(properties.timeout())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "documents.analysis.enabled", havingValue = "true")
    DocumentAnalyzer claudeDocumentAnalyzer(AnthropicClient client, AnalysisProperties properties) {
        return new ClaudeDocumentAnalyzer(client, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "documents.analysis.enabled", havingValue = "false", matchIfMissing = true)
    DocumentAnalyzer disabledDocumentAnalyzer() {
        return new DisabledDocumentAnalyzer();
    }
}
