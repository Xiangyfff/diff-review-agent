package com.codereview.agent.web;

import com.codereview.agent.eval.CorpusRepository;
import com.codereview.agent.investigate.InvestigateConfig;
import com.codereview.agent.model.ReviewModels;
import com.codereview.agent.model.TokenLedger;
import com.codereview.agent.review.Depth;
import com.codereview.agent.review.ReviewPipeline;
import com.codereview.agent.review.Sampling;
import com.codereview.agent.review.ReviewReport;
import com.codereview.agent.verify.VerifyConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.node.ArrayNode;

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
    private static final String DEMO_CASE = "null-guard-removed";

    private final ReviewPipeline pipeline;
    private CorpusRepository.Corpus corpus;

    public DemoServer(ReviewPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        for (String argument : args) {
            if (!argument.startsWith("--")) {
                port = Integer.parseInt(argument);
            }
        }
        ReviewModels models = ReviewModels.forArguments(java.util.Arrays.asList(args));
        ReviewPipeline pipeline = models == null
                ? ReviewPipeline.offline()
                : new ReviewPipeline(models, InvestigateConfig.defaults(), VerifyConfig.defaults());
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
        server.createContext("/api/defaults", this::handleDefaults);
        server.createContext("/api/commits", this::handleCommits);
        server.createContext("/api/files", this::handleFiles);
        server.createContext("/api/file", this::handleFile);
        server.createContext("/api/commit", this::handleCommit);
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
        String models = pipeline.modelDescription();
        answer.put("models", models);
        // A model on this machine answers in tens of seconds, not in one: say so before the first
        // click rather than letting the page look frozen.
        if (models.contains("127.0.0.1") || models.contains("localhost")) {
            answer.put("note", "本地模型：每次调用通常要几秒到几十秒，整次审查可能要几分钟。");
        }
        send(exchange, 200, "application/json; charset=utf-8", answer.toString());
    }

    /**
     * The page opens on something worth looking at: a corpus commit that removes a null check, so
     * the first click shows a real finding instead of an empty range. The corpus is built on demand
     * because the first request is also the first time we know the page is actually being used.
     */
    private void handleDefaults(HttpExchange exchange) throws IOException {
        ObjectNode answer = MAPPER.createObjectNode();
        try {
            CorpusRepository.Corpus ready = corpus();
            answer.put("repository", ready.workspace().toAbsolutePath().toString());
            answer.put("base", ready.base());
            answer.put("head", ready.commitOf(DEMO_CASE));
            answer.put("case", DEMO_CASE);
            send(exchange, 200, "application/json; charset=utf-8", answer.toString());
        } catch (Exception e) {
            answer.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            send(exchange, 500, "application/json; charset=utf-8", answer.toString());
        }
    }

    private synchronized CorpusRepository.Corpus corpus() throws Exception {
        if (corpus == null) {
            corpus = CorpusRepository.build(Path.of("target", "eval-corpus"));
        }
        return corpus;
    }


    /**
     * Recent commits of whatever repository the page is pointed at. The page uses this list to pick
     * the two revisions, so a visitor can review any range instead of typing object names.
     */
    private void handleCommits(HttpExchange exchange) throws IOException {
        ObjectNode answer = MAPPER.createObjectNode();
        try {
            Path repository = repositoryOf(exchange);
            ArrayNode commits = MAPPER.createArrayNode();
            try (Git git = Git.open(repository.toFile())) {
                for (RevCommit commit : git.log().setMaxCount(60).call()) {
                    ObjectNode node = commits.addObject();
                    node.put("id", commit.getName());
                    node.put("short", commit.abbreviate(7).name());
                    node.put("message", commit.getShortMessage());
                    node.put("time", Instant.ofEpochSecond(commit.getCommitTime()).toString());
                    node.put("author", commit.getAuthorIdent().getName());
                }
            }
            answer.set("commits", commits);
            send(exchange, 200, "application/json; charset=utf-8", answer.toString());
        } catch (Exception e) {
            answer.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            send(exchange, 400, "application/json; charset=utf-8", answer.toString());
        }
    }

    /** The java files a visitor may edit, so the demo can be driven without leaving the browser. */
    private void handleFiles(HttpExchange exchange) throws IOException {
        ObjectNode answer = MAPPER.createObjectNode();
        try {
            Path repository = repositoryOf(exchange);
            List<String> files = new ArrayList<>();
            for (String root : List.of("src/main/java", "src/test/java")) {
                Path start = repository.resolve(root);
                if (!Files.isDirectory(start)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(start)) {
                    walk.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java"))
                            .map(path -> repository.relativize(path).toString().replace('\\', '/'))
                            .sorted(Comparator.naturalOrder())
                            .forEach(files::add);
                }
            }
            ArrayNode nodes = answer.putArray("files");
            files.forEach(nodes::add);
            send(exchange, 200, "application/json; charset=utf-8", answer.toString());
        } catch (Exception e) {
            answer.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            send(exchange, 400, "application/json; charset=utf-8", answer.toString());
        }
    }

    private void handleFile(HttpExchange exchange) throws IOException {
        ObjectNode answer = MAPPER.createObjectNode();
        try {
            Path repository = repositoryOf(exchange);
            String relative = query(exchange, "path");
            Path file = inside(repository, relative);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("不是文件：" + relative);
            }
            answer.put("path", relative);
            answer.put("content", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            send(exchange, 200, "application/json; charset=utf-8", answer.toString());
        } catch (IllegalArgumentException e) {
            answer.put("error", e.getMessage());
            send(exchange, 400, "application/json; charset=utf-8", answer.toString());
        } catch (Exception e) {
            answer.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            send(exchange, 500, "application/json; charset=utf-8", answer.toString());
        }
    }

    /**
     * Write a file and commit it. This is the whole point of the demo: change something, commit,
     * and watch which hunks the pipeline picks up and what it says about them.
     */
    private void handleCommit(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "use POST");
            return;
        }
        ObjectNode answer = MAPPER.createObjectNode();
        try {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            Path repository = Path.of(text(request, "repository"));
            String relative = text(request, "path");
            String content = text(request, "content");
            String message = text(request, "message");
            if (message.isEmpty()) {
                message = "Edit " + relative;
            }
            Path file = inside(repository, relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            String commit;
            try (Git git = Git.open(repository.toFile())) {
                git.add().addFilepattern(relative).call();
                git.add().setUpdate(true).addFilepattern(relative).call();
                commit = git.commit().setMessage(message).call().getName();
            }
            answer.put("commit", commit);
            answer.put("short", commit.substring(0, 7));
            send(exchange, 200, "application/json; charset=utf-8", answer.toString());
        } catch (IllegalArgumentException e) {
            answer.put("error", e.getMessage());
            send(exchange, 400, "application/json; charset=utf-8", answer.toString());
        } catch (Exception e) {
            answer.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            send(exchange, 500, "application/json; charset=utf-8", answer.toString());
        }
    }

    private static Path repositoryOf(HttpExchange exchange) {
        String repository = query(exchange, "repository");
        if (repository.isEmpty()) {
            throw new IllegalArgumentException("缺少 repository 参数");
        }
        Path path = Path.of(repository).toAbsolutePath().normalize();
        if (!Files.isDirectory(path.resolve(".git"))) {
            throw new IllegalArgumentException("不是 git 仓库：" + repository);
        }
        return path;
    }

    private static Path inside(Path repository, String relative) {
        Path file = repository.resolve(relative).toAbsolutePath().normalize();
        if (!file.startsWith(repository)) {
            throw new IllegalArgumentException("路径超出了仓库：" + relative);
        }
        return file;
    }

    private static String query(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (key.equals(name)) {
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return "";
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
            Depth depth = Depth.parse(text(request, "depth"));
            JsonNode includeNode = request.get("include");
            List<String> include = new ArrayList<>();
            if (includeNode != null && includeNode.isArray()) {
                includeNode.forEach(node -> {
                    String value = node.asText("").strip();
                    if (!value.isEmpty()) {
                        include.add(value);
                    }
                });
            }
            double samplePercent = number(request, "sample", 0.0);
            long started = System.nanoTime();
            TokenLedger.reset();
            ReviewReport report = pipeline.sampling(Sampling.percent(samplePercent))
                    .run(path, base, head, depth, include);
            TokenLedger.Snapshot tokens = TokenLedger.snapshot();
            ObjectNode body = (ObjectNode) MAPPER.readTree(report.renderJson());
            body.put("repository", repository);
            body.put("models", pipeline.modelDescription());
            body.put("depth", depth.name());
            body.put("samplePercent", samplePercent);
            body.put("durationMs", (System.nanoTime() - started) / 1_000_000);
            ObjectNode usage = body.putObject("tokens");
            usage.put("calls", tokens.calls());
            usage.put("input", tokens.inputTokens());
            usage.put("output", tokens.outputTokens());
            usage.put("total", tokens.totalTokens());
            send(exchange, 200, "application/json; charset=utf-8", body.toPrettyString());
        } catch (IllegalArgumentException e) {
            answer.put("error", e.getMessage());
            send(exchange, 400, "application/json; charset=utf-8", answer.toString());
        } catch (RuntimeException e) {
            answer.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            send(exchange, 500, "application/json; charset=utf-8", answer.toString());
        }
    }

    private static double number(JsonNode request, String field, double fallback) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        double value = node.isNumber() ? node.asDouble() : Double.parseDouble(node.asText("0"));
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("抽查比例必须在 0 到 100 之间");
        }
        return value;
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
