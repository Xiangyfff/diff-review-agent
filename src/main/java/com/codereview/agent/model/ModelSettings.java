package com.codereview.agent.model;

/**
 * Where to find the model and under which name. Read from the environment so no key ever has to be
 * written into the repository, a test or a command line.
 */
public record ModelSettings(String baseUrl, String apiKey, String modelName, double temperature,
        int maxTokens) {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-chat";

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
