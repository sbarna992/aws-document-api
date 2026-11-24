package com.sandeep.awsdocumentapi.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.sandeep.awsdocumentapi.analysis.DocumentAnalysis.Finding;
import com.sandeep.awsdocumentapi.analysis.DocumentAnalysis.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Document analysis through Claude's Messages API (ADR 16).
 * <p>
 * One request per document: a system prompt, the document itself (a PDF as a base64 document block, plain
 * text as a text document block), one instruction, and one tool, {@value #TOOL_NAME}. The tool is never
 * executed — its input schema <em>is</em> the answer we want, so Claude "calling" it hands us structured
 * JSON in exactly that shape. {@code strict: true} makes the API guarantee the input matches the schema.
 * <p>
 * Forced tool choice ({@code tool_choice: tool}) is rejected by current Claude models, so the request uses the
 * default ({@code auto}) and the prompt tells Claude to call the tool. The rare answer without a tool call
 * becomes an {@link AnalysisFailedException}, like a refusal or a reply cut off by {@code max_tokens}.
 */
@Slf4j
public class ClaudeDocumentAnalyzer implements DocumentAnalyzer {

    static final String TOOL_NAME = "record_document_analysis";

    static final String SYSTEM_PROMPT = """
            You analyse documents for a document-management service. Report only what the document itself \
            says; never add facts, diagnoses or advice that are not written in it. Always respond by calling \
            the record_document_analysis tool exactly once.""";

    static final String INSTRUCTION = """
            Analyse this document. Identify what kind of document it is and summarise it in two or three \
            sentences. Then list each notable finding: a short category, a one-sentence description, the exact \
            words from the document that support it, and the page it appears on (null if the document has no \
            pages). Record the result with the record_document_analysis tool.""";

    static final Tool TOOL = analysisTool();

    private final AnthropicClient client;
    private final AnalysisProperties properties;

    public ClaudeDocumentAnalyzer(AnthropicClient client, AnalysisProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public DocumentAnalysis analyze(String filename, MediaType contentType, byte[] content) {
        MessageCreateParams request = buildRequest(filename, contentType, content);
        long started = System.nanoTime();
        Message reply;
        try {
            reply = client.messages().create(request);
        } catch (RateLimitException | InternalServerException | AnthropicIoException e) {
            // 429, 5xx (overloaded, gateway timeout, ...) and network failures. The SDK has already retried
            // these twice; give up and let the caller retry later.
            log.warn("Claude temporarily unavailable after retries: {}", e.toString());
            throw new AnalysisUnavailableException("The analysis service is temporarily unavailable; try again later", e);
        } catch (AnthropicServiceException e) {
            // 400/401/403/404/413...: a problem with our request or our key, never shown to the caller.
            log.error("Claude rejected the analysis request with HTTP {}", e.statusCode(), e);
            throw new AnalysisFailedException("The analysis service rejected the request", e);
        } catch (AnthropicException e) {
            log.error("Claude call failed", e);
            throw new AnalysisFailedException("The analysis service could not be reached", e);
        }

        DocumentAnalysis analysis = toAnalysis(reply);
        log.info("Analysed {} ({} bytes) with {} in {} ms: {} findings, {} input / {} output tokens",
                contentType, content.length, analysis.model(), (System.nanoTime() - started) / 1_000_000,
                analysis.findings().size(), analysis.usage().inputTokens(), analysis.usage().outputTokens());
        return analysis;
    }

    /** Package-private so the request can be checked without calling the API. */
    MessageCreateParams buildRequest(String filename, MediaType contentType, byte[] content) {
        return MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(properties.effort())).build())
                .system(SYSTEM_PROMPT)
                .addTool(TOOL)
                .addUserMessageOfContentBlockParams(List.of(
                        ContentBlockParam.ofDocument(documentBlock(filename, contentType, content)),
                        ContentBlockParam.ofText(INSTRUCTION)))
                .build();
    }

    /** Package-private so every kind of reply can be checked without calling the API. */
    DocumentAnalysis toAnalysis(Message reply) {
        StopReason stopReason = reply.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stopReason)) {
            log.warn("Claude declined to analyse a document: {}", reply.stopDetails());
            throw new AnalysisFailedException("The analysis service declined to analyse this document");
        }
        if (StopReason.MAX_TOKENS.equals(stopReason)) {
            throw new AnalysisFailedException("The analysis was cut off before it finished");
        }

        ToolUseBlock call = reply.content().stream()
                .flatMap(block -> block.toolUse().stream())
                .filter(toolUse -> TOOL_NAME.equals(toolUse.name()))
                .findFirst()
                .orElseThrow(() -> new AnalysisFailedException("The analysis service answered without recording an analysis"));

        ToolInput input;
        try {
            input = call._input().convert(ToolInput.class);
        } catch (RuntimeException e) {
            throw new AnalysisFailedException("The analysis did not have the expected structure", e);
        }
        if (input == null || isBlank(input.documentType()) || isBlank(input.summary()) || input.findings() == null
                || input.findings().stream().anyMatch(f -> f == null || isBlank(f.description()) || isBlank(f.evidence()))) {
            throw new AnalysisFailedException("The analysis did not have the expected structure");
        }

        return new DocumentAnalysis(reply.model().asString(), input.documentType(), input.summary(),
                List.copyOf(input.findings()),
                new TokenUsage(reply.usage().inputTokens(), reply.usage().outputTokens()));
    }

    /** The tool's input, exactly as the schema in {@link #analysisTool()} describes it. */
    record ToolInput(String documentType, String summary, List<Finding> findings) {
    }

    private static DocumentBlockParam documentBlock(String filename, MediaType contentType, byte[] content) {
        DocumentBlockParam.Builder block = DocumentBlockParam.builder().title(filename);
        if (MediaType.APPLICATION_PDF.equalsTypeAndSubtype(contentType)) {
            return block.base64Source(Base64.getEncoder().encodeToString(content)).build();
        }
        Charset charset = contentType.getCharset() != null ? contentType.getCharset() : StandardCharsets.UTF_8;
        return block.textSource(new String(content, charset)).build();
    }

    private static Tool analysisTool() {
        Map<String, Object> finding = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("category", "description", "evidence", "page"),
                "properties", Map.of(
                        "category", Map.of("type", "string",
                                "description", "Short label, e.g. 'abnormal result' or 'follow-up'"),
                        "description", Map.of("type", "string", "description", "One sentence"),
                        "evidence", Map.of("type", "string",
                                "description", "The exact words from the document that support this finding"),
                        "page", Map.of("type", List.of("integer", "null"),
                                "description", "1-based page number; null when the document has no pages")));

        return Tool.builder()
                .name(TOOL_NAME)
                .description("Record the analysis of the document. Every finding must quote the document's own "
                        + "words as evidence.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("documentType", JsonValue.from(Map.of(
                                        "type", "string", "description", "What kind of document this is")))
                                .putAdditionalProperty("summary", JsonValue.from(Map.of(
                                        "type", "string", "description", "Two or three sentences")))
                                .putAdditionalProperty("findings", JsonValue.from(Map.of(
                                        "type", "array", "items", finding)))
                                .build())
                        .required(List.of("documentType", "summary", "findings"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
