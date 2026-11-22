package com.codereview.agent.intake;

import com.codereview.agent.triage.model.AuthorIntent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pull request document is fetched with a plain request. These tests run against a local stub,
 * so the suite still needs no network and no token.
 */
class GitHubPullRequestTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void readsTheTitleAndDescriptionOfAPullRequest() {
        List<String> paths = new ArrayList<>();
        server.createContext("/repos/owner/name/pulls/7", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = ("{\"title\":\"Tighten the null guard\","
                    + "\"body\":\"A.find crashed on missing rows.\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        AuthorIntent intent = GitHubPullRequest.fetch(base, "owner/name", 7, null);

        assertThat(paths).containsExactly("/repos/owner/name/pulls/7");
        assertThat(intent.title()).isEqualTo("Tighten the null guard");
        assertThat(intent.body()).contains("crashed on missing rows");
        assertThat(intent.source()).isEqualTo("owner/name#7");
    }

    @Test
    void aMissingPullRequestSaysSoInsteadOfReturningAnEmptyIntent() {
        server.createContext("/repos/owner/name/pulls/9", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> GitHubPullRequest.fetch(base, "owner/name", 9, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no pull request 9");
    }

    @Test
    void aTokenIsSentOnlyWhenOneIsConfigured() {
        List<String> seen = new ArrayList<>();
        server.createContext("/repos/owner/name/pulls/1", exchange -> {
            seen.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"title\":\"x\",\"body\":\"\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        GitHubPullRequest.fetch(base, "owner/name", 1, "secret-token");
        GitHubPullRequest.fetch(base, "owner/name", 1, null);

        assertThat(seen.get(0)).isEqualTo("Bearer secret-token");
        assertThat(seen.get(1)).isEqualTo("null");
    }
}
