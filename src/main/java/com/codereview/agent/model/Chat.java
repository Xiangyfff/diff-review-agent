package com.codereview.agent.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.List;

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
     * An OpenAI-compatible endpoint. DeepSeek, Qwen and a local gateway all speak this protocol, so
     * the provider is a setting rather than a code path.
     */
    static Chat openAiCompatible(ModelSettings settings) {
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.apiKey())
                .modelName(settings.modelName())
                .temperature(settings.temperature())
                .maxTokens(settings.maxTokens())
                .responseFormat("json_object")
                .timeout(Duration.ofSeconds(120))
                .maxRetries(2)
                .logRequests(false)
                .logResponses(false)
                .build();
        return (system, user) -> {
            List<ChatMessage> messages = List.of(SystemMessage.from(system), UserMessage.from(user));
            ChatResponse response = model.chat(messages);
            if (response.tokenUsage() != null) {
                TokenLedger.record(response.tokenUsage().inputTokenCount(),
                        response.tokenUsage().outputTokenCount());
            }
            String text = response.aiMessage().text();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("the model returned an empty message");
            }
            return text;
        };
    }
}
