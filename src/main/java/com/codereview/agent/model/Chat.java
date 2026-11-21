package com.codereview.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The only file in the project that knows LangChain4j exists.
 *
 * <p>Everything above this line speaks in prompts and JSON, so swapping the SDK, the provider or the
 * transport means touching this class and nothing else. The chat is a plain function on purpose: a
 * deterministic replay or a canned reply is a one-line lambda, which is why the tests never touch the
 * network.
 */
@FunctionalInterface
public interface Chat {

    String complete(String system, String user);

    /**
     * Same question, but the answer is constrained to a schema by a forced tool call.
     *
     * <p>The default implementation falls back to plain completion so a stand-in or a replay still
     * works; the OpenAI-compatible implementation below overrides it with the real constraint.
     */
    default String completeStructured(String system, String user, String toolName,
                                      String toolDescription, String parametersJson) {
        return complete(system, user);
    }

    /**
     * An OpenAI-compatible endpoint. DeepSeek, Qwen and a local gateway all speak this protocol, so
     * the provider is a setting rather than a code path.
     */
    static Chat openAiCompatible(ModelSettings settings) {
        ChatModel plain = model(settings, "json_object");
        // The structured path must not also ask for a JSON response format: a provider that is
        // already forced to answer through a tool call rejects the two settings together.
        ChatModel structured = model(settings, null);
        ObjectMapper mapper = new ObjectMapper();
        return new Chat() {
            @Override
            public String complete(String system, String user) {
                ChatResponse response = plain.chat(messages(system, user));
                record(response);
                String text = response.aiMessage().text();
                if (text == null || text.isBlank()) {
                    throw new IllegalStateException("the model returned an empty message");
                }
                return text;
            }

            @Override
            public String completeStructured(String system, String user, String toolName,
                                             String toolDescription, String parametersJson) {
                ToolSpecification specification = ToolSpecification.fromJson(specification(
                        mapper, toolName, toolDescription, parametersJson));
                ChatRequest request = ChatRequest.builder()
                        .messages(messages(system, user))
                        .toolSpecifications(specification)
                        .toolChoice(ToolChoice.REQUIRED)
                        .build();
                ChatResponse response = structured.chat(request);
                record(response);
                List<ToolExecutionRequest> calls = response.aiMessage().toolExecutionRequests();
                if (calls == null || calls.isEmpty()) {
                    throw new IllegalStateException(
                            "the model did not call " + toolName + "; a plain reply cannot be used");
                }
                String arguments = calls.get(0).arguments();
                if (arguments == null || arguments.isBlank()) {
                    throw new IllegalStateException(toolName + " was called without arguments");
                }
                return arguments;
            }
        };
    }

    private static ChatModel model(ModelSettings settings, String responseFormat) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.apiKey())
                .modelName(settings.modelName())
                .temperature(settings.temperature())
                .maxTokens(settings.maxTokens())
                .timeout(Duration.ofSeconds(120))
                .maxRetries(2)
                .logRequests(false)
                .logResponses(false);
        if (responseFormat != null) {
            builder.responseFormat(responseFormat);
        }
        return builder.build();
    }

    private static List<ChatMessage> messages(String system, String user) {
        return List.of(SystemMessage.from(system), UserMessage.from(user));
    }

    private static void record(ChatResponse response) {
        if (response.tokenUsage() != null) {
            TokenLedger.record(response.tokenUsage().inputTokenCount(),
                    response.tokenUsage().outputTokenCount());
        }
    }

    private static String specification(ObjectMapper mapper, String toolName, String description,
                                        String parametersJson) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "name", toolName,
                    "description", description,
                    "parameters", mapper.readTree(parametersJson)));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid schema for " + toolName, e);
        }
    }
}
