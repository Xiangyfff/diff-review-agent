package com.codereview.agent.publish;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Posts a review to GitHub.
 *
 * <p>Dry run is the default everywhere it is offered: the payload is the interesting part, and
 * posting to somebody's pull request is not something a test or a demo should do by accident.
 */
public final class GitHubReviewer {

    private static final String DEFAULT_API = "https://api.github.com";

    private final String apiBase;
    private final String token;

    public GitHubReviewer(String apiBase, String token) {
        this.apiBase = apiBase == null || apiBase.isBlank() ? DEFAULT_API : apiBase.strip();
        this.token = token == null ? "" : token.strip();
    }

    public static GitHubReviewer fromEnvironment() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "GITHUB_TOKEN is not set; set it (a fine-grained token with pull-request write "
                            + "access) or run with --dry-run to only print the payload");
        }
        return new GitHubReviewer(System.getenv("GITHUB_API_URL"), token);
    }

    public String post(String slug, int pullNumber, ObjectNode payload)
            throws IOException, InterruptedException {
        URI uri = URI.create(apiBase + "/repos/" + slug + "/pulls/" + pullNumber + "/reviews");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("GitHub answered " + response.statusCode() + ": "
                    + response.body());
        }
        return response.body();
    }
}
