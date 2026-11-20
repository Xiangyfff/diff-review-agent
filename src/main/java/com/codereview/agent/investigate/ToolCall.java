package com.codereview.agent.investigate;

import java.util.Map;

/**
 * One request for a tool, with string arguments.
 *
 * <p>Arguments stay as strings because that is exactly what the model produces; individual tools
 * parse the ones they need and fail with a readable message rather than blowing up the loop.
 */
public record ToolCall(String tool, Map<String, String> arguments) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public String argument(String name) {
        return arguments.getOrDefault(name, "").trim();
    }

    public int intArgument(String name, int fallback) {
        try {
            return Integer.parseInt(argument(name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String describe() {
        return tool + arguments;
    }
}
