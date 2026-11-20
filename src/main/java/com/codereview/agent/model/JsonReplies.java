package com.codereview.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns whatever the model typed into a JSON object, or refuses.
 *
 * <p>Models wrap JSON in prose and in markdown fences often enough that stripping the fence is worth
 * doing; guessing at anything beyond that is not. If there is no object in the reply the caller gets
 * an exception, because a half-understood reply is worse than a failed call.
 */
public final class JsonReplies {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonReplies() {
    }

    public static JsonNode object(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("the reply was empty");
        }
        String text = stripFences(raw);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("the reply contains no JSON object");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text.substring(start, end + 1));
        } catch (Exception failure) {
            throw new IllegalArgumentException("the reply is not valid JSON: " + failure.getMessage(),
                    failure);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("the reply is JSON but not an object");
        }
        return node;
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    public static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : fallback;
    }

    public static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : fallback;
    }

    public static List<String> strings(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText().trim());
            }
        }
        return List.copyOf(out);
    }

    private static String stripFences(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence);
            }
        }
        return text;
    }
}
