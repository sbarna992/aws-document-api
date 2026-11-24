package com.sandeep.awsdocumentapi.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one test that really calls Claude. Skipped unless {@code DOCAPI_CLAUDE_IT=true}; needs
 * {@code ANTHROPIC_API_KEY}. Costs a few cents per run. Uses only synthetic documents.
 * <p>
 * LLM output varies between runs, so it checks what must always hold — findings exist, each quotes
 * evidence, the two out-of-range values in the synthetic lab report are found — not exact wording.
 */
@EnabledIfEnvironmentVariable(named = "DOCAPI_CLAUDE_IT", matches = "true")
class ClaudeDocumentAnalyzerIntegrationTest {

    private static AnthropicClient client;
    private static ClaudeDocumentAnalyzer analyzer;

    @BeforeAll
    static void connect() {
        AnalysisProperties properties = new AnalysisProperties(true, "claude-opus-5-5", "medium", 16000, Duration.ofMinutes(2));
        client = AnthropicOkHttpClient.builder().fromEnv().timeout(properties.timeout()).build();
        analyzer = new ClaudeDocumentAnalyzer(client, properties);
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Test
    void findsTheOutOfRangeResultsInTheSyntheticLabReport() throws IOException {
        byte[] pdf = new ClassPathResource("analysis/test-lab-report.pdf").getContentAsByteArray();

        DocumentAnalysis analysis = analyzer.analyze("test-lab-report.pdf", MediaType.APPLICATION_PDF, pdf);

        assertThat(analysis.documentType()).isNotBlank();
        assertThat(analysis.summary()).isNotBlank();
        assertThat(analysis.findings()).isNotEmpty().allSatisfy(finding -> {
            assertThat(finding.evidence()).isNotBlank();
            assertThat(finding.page()).isBetween(1, 2);
        });
        assertThat(analysis.findings()).anySatisfy(finding -> assertThat(finding.evidence()).contains("112"));
        assertThat(analysis.findings()).anySatisfy(finding -> assertThat(finding.evidence()).contains("142"));
        assertThat(analysis.usage().inputTokens()).isPositive();
    }

    @Test
    void quotesEvidenceFromPlainText() {
        String notes = """
                Project kickoff notes, 3 November 2026.
                The vendor contract expires on 2026-12-31 and must be renewed before then.
                Budget approved: 40,000 USD. Next review meeting is on 2026-11-17.
                """;

        DocumentAnalysis analysis = analyzer.analyze("kickoff-notes.txt", MediaType.TEXT_PLAIN,
                notes.getBytes(StandardCharsets.UTF_8));

        assertThat(analysis.findings()).isNotEmpty()
                .anySatisfy(finding -> assertThat(finding.evidence()).contains("2026-12-31"));
    }
}
