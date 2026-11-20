package com.codereview.agent.investigate;

import com.codereview.agent.triage.index.SymbolIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the repository under review.
 *
 * <p>Every path the model supplies goes through {@link #resolve(String)}, which refuses absolute
 * paths and anything climbing out of the root. The review process must not be able to touch the
 * machine, and a model that hallucinates {@code ../../secrets} should get an error, not a file.
 */
public final class RepoAccess {

    private static final int MAX_READ_LINES = 400;

    private final Path root;
    private final SymbolIndex index;

    public RepoAccess(Path root, SymbolIndex index) {
        this.root = root.toAbsolutePath().normalize();
        this.index = index;
    }

    public Path root() {
        return root;
    }

    public SymbolIndex index() {
        return index;
    }

    /**
     * @return the file, or empty when the model named something outside the repository
     */
    public Optional<Path> resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            return Optional.empty();
        }
        String cleaned = relative.replace('\\', '/').trim();
        if (cleaned.startsWith("/") || cleaned.matches("^[A-Za-z]:.*")) {
            return Optional.empty();
        }
        Path candidate = root.resolve(cleaned).normalize();
        if (!candidate.startsWith(root)) {
            return Optional.empty();
        }
        return Files.isRegularFile(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    public boolean exists(String relative) {
        return resolve(relative).isPresent();
    }

    public List<String> readLines(String relative) {
        Optional<Path> file = resolve(relative);
        if (file.isEmpty()) {
            throw new IllegalArgumentException("no such file inside the repository: " + relative);
        }
        try {
            return Files.readAllLines(file.get(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param start one-based first line, clamped into the file
     * @param end   one-based last line, clamped and capped to keep prompts small
     */
    public Text read(String relative, int start, int end) {
        List<String> lines = readLines(relative);
        int first = Math.max(1, start);
        int last = Math.min(lines.size(), Math.max(first, end));
        if (last - first + 1 > MAX_READ_LINES) {
            last = first + MAX_READ_LINES - 1;
        }
        if (lines.isEmpty() || first > lines.size()) {
            return new Text(relative, first, first, "");
        }
        StringBuilder text = new StringBuilder();
        for (int line = first; line <= last; line++) {
            text.append(line).append(": ").append(lines.get(line - 1)).append('\n');
        }
        return new Text(relative, first, last, text.toString().stripTrailing());
    }

    public int lineCount(String relative) {
        return readLines(relative).size();
    }

    public record Text(String file, int startLine, int endLine, String text) {
    }
}
