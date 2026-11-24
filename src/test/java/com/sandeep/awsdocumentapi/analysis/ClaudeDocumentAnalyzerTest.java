package com.sandeep.awsdocumentapi.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.sandeep.awsdocumentapi.analysis.DocumentAnalysis.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Offline: no key, no network, no cost. Checks the request we build and how every kind of reply is
 * handled. Replies are real Messages API JSON, parsed by the SDK's own mapper.
 */
class ClaudeDocumentAnalyzerTest {

    private static final AnalysisProperties PROPERTIES =
            new AnalysisProperties(true, "claude-opus-5-5", "medium", 16000, Duration.ofMinutes(2));

    private final AnthropicClient client = mock(AnthropicClient.class);
    private final MessageService messages = mock(MessageService.class);
    private final ClaudeDocumentAnalyzer analyzer = new ClaudeDocumentAnalyzer(client, PROPERTIES);

    @BeforeEach
    void wireClient() {
        given(client.messages()).willReturn(messages);
    }

    // --- the request ---

    @Test
    void requestSendsPdfAsBase64DocumentWithStrictToolAndConfiguredSettings() {
        byte[] pdf = "%PDF-1.7 not really a pdf".getBytes(StandardCharsets.US_ASCII);

        JsonNode body = json(analyzer.buildRequest("report.pdf", MediaType.APPLICATION_PDF, pdf));

        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("max_tokens").asLong()).isEqualTo(16000);
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("medium");
        assertThat(body.has("tool_choice")).as("forced tool choice is rejected by current models").isFalse();
        assertThat(body.path("system").asText()).contains("record_document_analysis");

        JsonNode tool = body.path("tools").get(0);
        assertThat(tool.path("name").asText()).isEqualTo("record_document_analysis");
        assertThat(tool.path("strict").asBoolean()).isTrue();
        JsonNode schema = tool.path("input_schema");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("required").toString()).contains("documentType", "summary", "findings");
        JsonNode findingSchema = schema.path("properties").path("findings").path("items");
        assertThat(findingSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(findingSchema.path("required").toString()).contains("category", "description", "evidence", "page");

        JsonNode content = body.path("messages").get(0).path("content");
        JsonNode document = content.get(0);
        assertThat(document.path("type").asText()).isEqualTo("document");
        assertThat(document.path("title").asText()).isEqualTo("report.pdf");
        assertThat(document.path("source").path("type").asText()).isEqualTo("base64");
        assertThat(document.path("source").path("media_type").asText()).isEqualTo("application/pdf");
        assertThat(document.path("source").path("data").asText()).isEqualTo(Base64.getEncoder().encodeToString(pdf));
        assertThat(content.get(1).path("type").asText()).isEqualTo("text");
    }

    @Test
    void requestSendsPlainTextAsTextDocument() {
        String text = "Renew the contract by 2026-12-31.";

        JsonNode document = json(analyzer.buildRequest("notes.txt", MediaType.TEXT_PLAIN,
                text.getBytes(StandardCharsets.UTF_8))).path("messages").get(0).path("content").get(0);

        assertThat(document.path("source").path("type").asText()).isEqualTo("text");
        assertThat(document.path("source").path("media_type").asText()).isEqualTo("text/plain");
        assertThat(document.path("source").path("data").asText()).isEqualTo(text);
    }

    // --- the reply ---

    @Test
    void toolCallBecomesTheAnalysis() {
        given(messages.create(any(MessageCreateParams.class))).willReturn(reply("tool_use", """
                [{"type": "text", "text": "Recording the analysis."},
                 {"type": "tool_use", "id": "toolu_1", "name": "record_document_analysis", "input": {
                   "documentType": "lab report",
                   "summary": "Routine panel with two values out of range.",
                   "findings": [
                     {"category": "abnormal result", "description": "Fasting glucose above range",
                      "evidence": "Glucose, fasting 112 mg/dL 70 - 99 H", "page": 1},
                     {"category": "follow-up", "description": "Repeat tests in three months",
                      "evidence": "Repeat fasting glucose and lipid panel in 3 months", "page": null}]}}]"""));

        DocumentAnalysis analysis = analyzer.analyze("report.pdf", MediaType.APPLICATION_PDF, new byte[]{1});

        assertThat(analysis.model()).isEqualTo("claude-opus-5-5");
        assertThat(analysis.documentType()).isEqualTo("lab report");
        assertThat(analysis.findings()).containsExactly(
                new Finding("abnormal result", "Fasting glucose above range", "Glucose, fasting 112 mg/dL 70 - 99 H", 1),
                new Finding("follow-up", "Repeat tests in three months",
                        "Repeat fasting glucose and lipid panel in 3 months", null));
        assertThat(analysis.usage().inputTokens()).isEqualTo(1200);
        assertThat(analysis.usage().outputTokens()).isEqualTo(340);
    }

    @Test
    void refusalIsAFailedAnalysis() {
        given(messages.create(any(MessageCreateParams.class))).willReturn(reply("refusal", "[]"));

        assertThatThrownBy(() -> analyzer.analyze("x.pdf", MediaType.APPLICATION_PDF, new byte[]{1}))
                .isInstanceOf(AnalysisFailedException.class).hasMessageContaining("declined");
    }

    @Test
    void replyCutOffByMaxTokensIsAFailedAnalysis() {
        given(messages.create(any(MessageCreateParams.class))).willReturn(reply("max_tokens", "[]"));

        assertThatThrownBy(() -> analyzer.analyze("x.pdf", MediaType.APPLICATION_PDF, new byte[]{1}))
                .isInstanceOf(AnalysisFailedException.class).hasMessageContaining("cut off");
    }

    @Test
    void textAnswerWithoutToolCallIsAFailedAnalysis() {
        given(messages.create(any(MessageCreateParams.class))).willReturn(reply("end_turn", """
                [{"type": "text", "text": "This is a lab report."}]"""));

        assertThatThrownBy(() -> analyzer.analyze("x.pdf", MediaType.APPLICATION_PDF, new byte[]{1}))
                .isInstanceOf(AnalysisFailedException.class).hasMessageContaining("without recording");
    }

    @Test
    void toolInputOfTheWrongShapeIsAFailedAnalysis() {
        given(messages.create(any(MessageCreateParams.class))).willReturn(reply("tool_use", """
                [{"type": "tool_use", "id": "toolu_1", "name": "record_document_analysis",
                  "input": {"documentType": "memo", "summary": "A memo.", "findings": "none"}}]"""));

        assertThatThrownBy(() -> analyzer.analyze("x.pdf", MediaType.APPLICATION_PDF, new byte[]{1}))
                .isInstanceOf(AnalysisFailedException.class).hasMessageContaining("expected structure");
    }

    @Test
    void findingWithoutEvidenceIsAFailedAnalysis() {
        given(messages.create(any(MessageCreateParams.class))).willReturn(reply("tool_use", """
                [{"type": "tool_use", "id": "toolu_1", "name": "record_document_analysis",
                  "input": {"documentType": "memo", "summary": "A memo.", "findings": [
                    {"category": "c", "description": "d", "evidence": " ", "page": 1}]}}]"""));

        assertThatThrownBy(() -> analyzer.analyze("x.pdf", MediaType.APPLICATION_PDF, new byte[]{1}))
                .isInstanceOf(AnalysisFailedException.class).hasMessageContaining("expected structure");
    }

    // --- errors from the API ---

    @Test
    void rateLimitAfterRetriesMeansTemporarilyUnavailable() {
        given(messages.create(any(MessageCreateParams.class))).willThrow(mock(RateLimitException.class));

        assertThatThrownBy(() -> analyzer.analyze("x.pdf", MediaType.APPLICATION_PDF, new byte[]{1}))
                .isInstanceOf(AnalysisUnavailableException.class);
    }

    @Test
    void rejectedKeyIsAFailedAnalysisNotAClientError() {
        given(messages.create(any(MessageCreateParams.class))).willThrow(mock(UnauthorizedException.class));

        assertThatThrownBy(() -> analyzer.analyze("x.pdf", MediaType.APPLICATION_PDF, new byte[]{1}))
                .isInstanceOf(AnalysisFailedException.class).hasMessageContaining("rejected the request");
    }

    private static JsonNode json(MessageCreateParams params) {
        return ObjectMappers.jsonMapper().valueToTree(params._body());
    }

    private static Message reply(String stopReason, String contentJson) {
        String json = """
                {"id": "msg_test", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "content": %s, "stop_reason": "%s", "stop_sequence": null,
                 "usage": {"input_tokens": 1200, "output_tokens": 340}}""".formatted(contentJson, stopReason);
        try {
            return ObjectMappers.jsonMapper().readValue(json, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException("bad test fixture", e);
        }
    }
}
