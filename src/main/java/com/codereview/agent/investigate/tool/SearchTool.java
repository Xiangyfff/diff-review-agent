package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Literal text search over the repository's source files.
 *
 * <p>Useful when the model wants to see how a string is used without knowing its name, for example
 * a message key or a column name. Hits are capped and grouped per file so one noisy file cannot
 * flood the prompt.
 */
public final class SearchTool implements ReviewTool {

    private static final int MAX_HITS = 20;
    private static final int MAX_FILE_BYTES = 1_000_000;

    private final RepoAccess repository;

    public SearchTool(RepoAccess repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String purpose() {
        return "Search the repository's source files for a literal string and show matching lines.";
    }

    @Override
    public String arguments() {
        return "text (literal, case-sensitive)";
    }

    @Override
    public ToolOutcome run(ToolCall call) {
        String needle = call.argument("text");
        if (needle.isEmpty()) {
            return ToolOutcome.failed("text is required");
        }
        List<ToolOutcome.Excerpt> excerpts = new ArrayList<>();
        int hits = 0;
        StringBuilder joined = new StringBuilder();
        try (Stream<Path> paths = Files.walk(repository.root())) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (hits == MAX_HITS) {
                    break;
                }
                if (!isSource(path) || size(path) > MAX_FILE_BYTES
                        || isSkipped(repository.root().relativize(path))) {
                    continue;
                }
                String relative = repository.root().relativize(path).toString().replace('\\', '/');
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (int line = 1; line <= lines.size() && hits < MAX_HITS; line++) {
                    if (lines.get(line - 1).contains(needle)) {
                        String hit = relative + ":" + line + ": " + lines.get(line - 1).trim();
                        joined.append(hits == 0 ? "" : "\n").append(hit);
                        hits++;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (hits == 0) {
            return ToolOutcome.failed("no line contains " + needle);
        }
        String file = hits == MAX_HITS ? "<search: capped>" : "<search>";
        excerpts.add(new ToolOutcome.Excerpt(file, 0, 0, joined.toString()));
        return ToolOutcome.of(hits + " line(s) contain " + needle, excerpts);
    }

    private static boolean isSource(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".properties")
                || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".sql");
    }

    /**
     * @param relative path relative to the repository root, so a checkout that happens to live
     *                 under a directory called "target" is not skipped wholesale
     */
    private static boolean isSkipped(Path relative) {
        for (Path segment : relative) {
            String name = segment.toString();
            if (name.equals(".git") || name.equals("target") || name.equals("build")
                    || name.equals("node_modules") || name.equals(".idea")) {
                return true;
            }
        }
        return false;
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }
}
