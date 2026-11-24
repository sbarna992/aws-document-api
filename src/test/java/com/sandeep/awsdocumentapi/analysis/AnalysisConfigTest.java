package com.sandeep.awsdocumentapi.analysis;

import com.anthropic.client.AnthropicClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Analysis is off unless switched on, and when off no Anthropic client exists — so no key is ever needed. */
class AnalysisConfigTest {

    @Configuration
    @EnableConfigurationProperties(AnalysisProperties.class)
    static class Properties {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, AnalysisConfig.class);

    @Test
    void offByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DocumentAnalyzer.class);
            assertThat(context.getBean(DocumentAnalyzer.class)).isInstanceOf(DisabledDocumentAnalyzer.class);
            assertThat(context).doesNotHaveBean(AnthropicClient.class);
        });
    }

    @Test
    void disabledAnalyzerRefusesAsUnavailable() {
        runner.withPropertyValues("documents.analysis.enabled=false").run(context ->
                assertThatThrownBy(() -> context.getBean(DocumentAnalyzer.class)
                        .analyze("x.pdf", MediaType.APPLICATION_PDF, new byte[]{1}))
                        .isInstanceOf(AnalysisUnavailableException.class)
                        .hasMessageContaining("not enabled"));
    }
}
