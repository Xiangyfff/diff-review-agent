package com.codereview.agent.web;

import com.codereview.agent.investigate.InvestigateConfig;
import com.codereview.agent.model.ReviewModels;
import com.codereview.agent.review.ReviewPipeline;
import com.codereview.agent.review.ReviewReport;
import com.codereview.agent.verify.VerifyConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * A one-page demo: paste a repository path and two revisions, see what the pipeline decided.
 *
 * <p>Deliberately tiny and local-only. It binds to the loopback interface, uses the deterministic
 * offline models so a page refresh costs nothing, and serves a single static file. This is a demo,
 * not a product: no accounts, no history, no upload.
 */
public final class DemoServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_PORT = 8099;

    private final ReviewPipeline pipeline;

    public DemoServer(ReviewPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        boolean real = false;
        for (String argument : args) {
            if ("--real".equals(argument)) {
                real = true;
            } else {
                port = Integer.parseInt(argument);
            }
        }
        ReviewPipeline pipeline = real
                ? new ReviewPipeline(ReviewModels.fromEnvironment(), InvestigateConfig.defaults(),
                        VerifyConfig.defaults())
                : ReviewPipeline.offline();
        DemoServer server = new DemoServer(pipeline);
        HttpServer http = server.start(port);
        System.out.println("demo page: http://127.0.0.1:" + http.getAddress().getPort() + "/");
        System.out.println("press Ctrl+C to stop");
    }

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handlePage);
        server.createContext("/api/review", this::handleReview);
        server.createContext("/api/mode", this::handleMode);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            send(exchange, 404, "text/plain; charset=utf-8", "not found");
            return;
        }
        try (InputStream page = DemoServer.class.getResourceAsStream("/web/index.html")) {
            if (page == null) {
                send(exchange, 500, "text/plain; charset=utf-8", "index.html is missing from the jar");
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8",
                    new String(page.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void handleMode(HttpExchange exchange) throws IOException {
        ObjectNode answer = MAPPER.createObjectNode();
        answer.put("models", pipeline.modelDescription());
        send(exchange, 200, "application/json; charset=utf-8", answer.toString());
    }

    private void handleReview(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "use POST");
            return;
        }
        ObjectNode answer = MAPPER.createObjectNode();
        try {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            String repository = text(request, "repository");
            String base = text(request, "base");
            String head = text(request, "head");
            if (repository.isEmpty() || base.isEmpty() || head.isEmpty()) {
                throw new IllegalArgumentException("仓库路径、起始提交、结束提交都不能为空");
            }
            Path path = Path.of(repository);
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("不是目录：" + repository);
            }
            long started = System.nanoTime();
            ReviewReport report = pipeline.run(path, base, head);
            ObjectNode body = (ObjectNode) MAPPER.readTree(report.renderJson());
            body.put("repository", repository);
            body.put("models", pipeline.modelDescription());
            body.put("durationMs", (System.nanoTime() - started) / 1_000_000);
            send(exchange, 200, "application/json; charset=utf-8", body.toPrettyString());
        } catch (IllegalArgumentException e) {
            answer.put("error", e.getMessage());
            send(exchange, 400, "application/json; charset=utf-8", answer.toString());
        } catch (RuntimeException e) {
            answer.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            send(exchange, 500, "application/json; charset=utf-8", answer.toString());
        }
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
