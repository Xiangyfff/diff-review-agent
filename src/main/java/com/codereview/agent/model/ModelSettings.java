package com.codereview.agent.model;

/**
 * Where to find the model and under which name. Read from the environment so no key ever has to be
 * written into the repository, a test or a command line.
 */
public record ModelSettings(String baseUrl, String apiKey, String modelName, double temperature,
        int maxTokens) {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    /** Ollama serves the same OpenAI-compatible protocol on this port. */
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://127.0.0.1:11434/v1";

    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5-coder:3b";

    public ModelSettings {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("an API key is required");
        }
    }

    public static ModelSettings fromEnvironment() {
        String apiKey = firstNonBlank(System.getenv("CODEREVIEW_LLM_API_KEY"),
                System.getenv("DEEPSEEK_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException("no API key: set CODEREVIEW_LLM_API_KEY "
                    + "(or DEEPSEEK_API_KEY) to run against a real model");
        }
        return new ModelSettings(
                nonBlankOr(System.getenv("CODEREVIEW_LLM_BASE_URL"), DEFAULT_BASE_URL),
                apiKey,
                nonBlankOr(System.getenv("CODEREVIEW_LLM_MODEL"), DEFAULT_MODEL),
                0.0,
                2000);
    }

    /**
     * A model running on this machine. Ollama speaks the same protocol as the hosted providers, so
     * the pipeline needs no second code path; only the address, the name and the timeout differ.
     *
     * <p>The key is a placeholder because Ollama ignores it but the client demands one.
     */
    public static ModelSettings ollama() {
        return new ModelSettings(
                nonBlankOr(System.getenv("CODEREVIEW_OLLAMA_BASE_URL"), DEFAULT_OLLAMA_BASE_URL),
                "ollama",
                nonBlankOr(System.getenv("CODEREVIEW_OLLAMA_MODEL"), DEFAULT_OLLAMA_MODEL),
                0.0,
                2000);
    }

    /**
     * Safe to log: the key is deliberately not part of it.
     */
    public String describe() {
        return modelName + " at " + baseUrl;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    private static String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
