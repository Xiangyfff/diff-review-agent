package com.codereview.agent.intake;

import com.codereview.agent.model.JsonReplies;
import com.codereview.agent.triage.model.AuthorIntent;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reads a pull request's title and description over plain HTTP, so the intent scan has the
 * author's own statement instead of guessing it from file names.
 *
 * <p>This is not an MCP server. MCP exists so tools can be discovered and called across processes;
 * reading one document from one endpoint is a request, and adding a protocol between the pipeline
 * and that request would buy nothing and cost a subprocess, a serialisation format and a new class
 * of failure. The description is also untrusted text: it reaches the prompt labelled as the
 * author's claim and never as evidence, so a description that says "this is only a log change"
 * cannot launder a transaction-boundary change.
 */
public final class GitHubPullRequest {

    private static final String DEFAULT_API = "https://api.github.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private GitHubPullRequest() {
    }

    public static String defaultApi() {
        String configured = System.getenv("CODEREVIEW_GITHUB_API");
        return configured == null || configured.isBlank() ? DEFAULT_API : configured.strip();
    }

    public static AuthorIntent fetch(String repository, int number) {
        return fetch(defaultApi(), repository, number, System.getenv("CODEREVIEW_GITHUB_TOKEN"));
    }

    /** @param repository "owner/name" */
    static AuthorIntent fetch(String api, String repository, int number, String token) {
        if (repository == null || !repository.contains("/")) {
            throw new IllegalArgumentException("expected the repository as owner/name, got " + repository);
        }
        URI uri = URI.create(api + "/repos/" + repository.strip() + "/pulls/" + number);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "diff-review-agent")
                .GET();
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token.strip());
        }
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException failed) {
            throw new IllegalStateException("could not reach " + uri + ": " + failed.getMessage(), failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading " + uri, interrupted);
        }
        if (response.statusCode() == 404) {
            throw new IllegalStateException("no pull request " + number + " in " + repository);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("GitHub answered " + response.statusCode() + " for " + uri);
        }
        JsonNode node = JsonReplies.object(response.body());
        return new AuthorIntent(JsonReplies.text(node, "title"), JsonReplies.text(node, "body"),
                repository + "#" + number);
    }
}
