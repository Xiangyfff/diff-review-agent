package com.codereview.agent.triage.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The embedding model, called over HTTP. Ollama's native endpoint is used rather than the
 * OpenAI-compatible one because the native one is what a local install actually serves.
 *
 * <p>Embeddings are cheap and deterministic, so results are memoised per text: re-running a review
 * over the same repository must not pay twice for the same policy chunk or the same query.
 */
public final class OllamaEmbedder implements Embedder {

    public static final String DEFAULT_URL = "http://127.0.0.1:11434/api/embeddings";
    public static final String DEFAULT_MODEL = "bge-m3";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String url;
    private final String model;
    private final HttpClient client;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();

    public OllamaEmbedder(String url, String model) {
        this.url = url;
        this.model = model;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Reads {@code CODEREVIEW_EMBED_URL} and {@code CODEREVIEW_EMBED_MODEL}, with local defaults. */
    public static OllamaEmbedder fromEnvironment() {
        return new OllamaEmbedder(
                System.getenv().getOrDefault("CODEREVIEW_EMBED_URL", DEFAULT_URL),
                System.getenv().getOrDefault("CODEREVIEW_EMBED_MODEL", DEFAULT_MODEL));
    }

    public String describe() {
        return model + " at " + url;
    }

    @Override
    public float[] embed(String text) {
        float[] cached = cache.get(text);
        if (cached != null) {
            return cached.clone();
        }
        float[] vector = request(text);
        cache.put(text, vector);
        return vector.clone();
    }

    private float[] request(String text) {
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            body.put("prompt", text);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("embedding endpoint returned " + response.statusCode()
                        + " for model " + model);
            }
            JsonNode root = JSON.readTree(response.body());
            JsonNode values = root.path("embedding");
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException("embedding response has no vector");
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            return vector;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding call interrupted", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("embedding call failed: " + failure.getMessage(), failure);
        }
    }
}
