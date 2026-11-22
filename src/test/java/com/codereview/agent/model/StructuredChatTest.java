package com.codereview.agent.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The degradation path matters more than the happy one: a local model is the normal case for a
 * refusal, and a refusal must cost one call, not one per prompt.
 */
class StructuredChatTest {

    private static final String TOOL = "emit_turn";
    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Test
    void aProviderThatCallsToolsIsUsedAsIs() {
        List<String> calls = new ArrayList<>();
        Chat delegate = new Chat() {
            @Override
            public String complete(String system, String user) {
                calls.add("plain");
                return "{\"from\":\"plain\"}";
            }

            @Override
            public String completeStructured(String system, String user, String toolName,
                                             String toolDescription, String parametersJson) {
                calls.add("structured");
                return "{\"from\":\"tool\"}";
            }
        };

        String answer = new StructuredChat(delegate)
                .completeStructured("sys", "question", TOOL, "describe", SCHEMA);

        assertThat(answer).isEqualTo("{\"from\":\"tool\"}");
        assertThat(calls).containsExactly("structured");
    }

    @Test
    void aProseAnswerSwitchesToTheSchemaInWordsAndStaysSwitched() {
        List<String> calls = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        Chat delegate = new Chat() {
            @Override
            public String complete(String system, String user) {
                calls.add("plain");
                prompts.add(user);
                return "{\"fallback\":true}";
            }

            @Override
            public String completeStructured(String system, String user, String toolName,
                                             String toolDescription, String parametersJson) {
                calls.add("structured");
                throw new IllegalStateException("the model did not call " + toolName
                        + "; a plain reply cannot be used");
            }
        };

        Chat chat = new StructuredChat(delegate);

        assertThat(chat.completeStructured("sys", "first", TOOL, "describe", SCHEMA))
                .isEqualTo("{\"fallback\":true}");
        assertThat(chat.completeStructured("sys", "second", TOOL, "describe", SCHEMA))
                .isEqualTo("{\"fallback\":true}");

        assertThat(calls).containsExactly("structured", "plain", "plain");
        assertThat(prompts.get(0)).contains("first").contains(TOOL).contains(SCHEMA)
                .contains("JSON object");
    }

    @Test
    void anEndpointThatRefusesToolsAlsoFallsBack() {
        Chat delegate = new Chat() {
            @Override
            public String complete(String system, String user) {
                return "{\"fallback\":true}";
            }

            @Override
            public String completeStructured(String system, String user, String toolName,
                                             String toolDescription, String parametersJson) {
                throw new IllegalArgumentException("this model does not support tools");
            }
        };

        assertThat(new StructuredChat(delegate)
                .completeStructured("sys", "q", TOOL, "describe", SCHEMA))
                .isEqualTo("{\"fallback\":true}");
    }

    @Test
    void anOrdinaryFailureIsNotMistakenForAMissingCapability() {
        Chat delegate = new Chat() {
            @Override
            public String complete(String system, String user) {
                return "{}";
            }

            @Override
            public String completeStructured(String system, String user, String toolName,
                                             String toolDescription, String parametersJson) {
                throw new IllegalStateException("the model returned an empty message");
            }
        };

        assertThatThrownBy(() -> new StructuredChat(delegate)
                .completeStructured("sys", "q", TOOL, "describe", SCHEMA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty message");
    }

    @Test
    void plainCompletionIsNeverRewritten() {
        Chat delegate = new Chat() {
            @Override
            public String complete(String system, String user) {
                return "plain answer for " + user;
            }
        };

        assertThat(new StructuredChat(delegate).complete("sys", "hi")).isEqualTo("plain answer for hi");
    }
}
