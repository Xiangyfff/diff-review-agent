package com.codereview.agent.triage.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The policy documents of a repository, cut into heading-sized pieces.
 *
 * <p>Cutting on headings rather than on a fixed window is deliberate: a heading is the unit a
 * human wrote deliberately, so an excerpt always reads as a complete rule instead of half of two.
 *
 * <p>A heading may carry a mandatory marker ({@code [强制]} or {@code [mandatory]}). That marker
 * is the document's own statement that any change touching the section has to be checked; it is
 * what lets a policy hit gate the tier without asking a model.
 */
public final class PolicyLibrary {

    public static final int MAX_CHUNK_CHARS = 1500;
    private static final String[] MANDATORY_MARKERS = {"[强制]", "[mandatory]"};

    private final List<PolicyChunk> chunks;
    private final int files;
    private final List<String> skipped;

    private PolicyLibrary(List<PolicyChunk> chunks, int files, List<String> skipped) {
        this.chunks = List.copyOf(chunks);
        this.files = files;
        this.skipped = List.copyOf(skipped);
    }

    /** Builds a library from pre-cut chunks; used by tests that exercise retrieval without files. */
    static PolicyLibrary of(List<PolicyChunk> chunks) {
        return new PolicyLibrary(chunks, chunks.isEmpty() ? 0 : 1, List.of());
    }

    public static PolicyLibrary empty() {
        return new PolicyLibrary(List.of(), 0, List.of());
    }

    public List<PolicyChunk> chunks() {
        return chunks;
    }

    public int files() {
        return files;
    }

    /** Files that looked like policy but could not be read; reported instead of swallowed. */
    public List<String> skipped() {
        return skipped;
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /**
     * Reads every {@code .md} file under the directory, in name order so the chunk ids are the same
     * on every run. A missing directory is not an error: a repository without policy documents
     * simply has no policy signal.
     */
    public static PolicyLibrary load(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return empty();
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(directory)) {
            files = walk.filter(path -> Files.isRegularFile(path))
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            return new PolicyLibrary(List.of(), 0, List.of(directory + ": " + unreadable.getMessage()));
        }

        List<PolicyChunk> chunks = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Path file : files) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String name = directory.relativize(file).toString().replace('\\', '/');
                chunks.addAll(split(name, text));
            } catch (IOException unreadable) {
                skipped.add(file + ": " + unreadable.getMessage());
            }
        }
        return new PolicyLibrary(chunks, files.size(), skipped);
    }

    /** Splits one document on its headings. Exposed for tests. */
    static List<PolicyChunk> split(String file, String text) {
        String base = file.replaceAll("\\.[^.]*$", "").replaceAll("[^A-Za-z0-9]+", "-");
        List<PolicyChunk> chunks = new ArrayList<>();
        String heading = null;
        boolean mandatory = false;
        StringBuilder body = new StringBuilder();
        List<String> examples = new ArrayList<>();
        StringBuilder sample = null;
        int counter = 0;
        for (String line : text.split("\\R")) {
            boolean fence = line.strip().startsWith("```");
            if (fence) {
                if (sample == null) {
                    sample = new StringBuilder();
                    continue;
                }
                String code = sample.toString().strip();
                if (!code.isEmpty()) {
                    examples.add(code);
                }
                sample = null;
                continue;
            }
            if (sample != null) {
                sample.append(line).append('\n');
                continue;
            }
            if (line.startsWith("#")) {
                if (heading != null && add(chunks, file, base, counter + 1, heading, mandatory,
                        body.toString(), examples)) {
                    counter++;
                }
                String raw = line.replaceAll("^#+\\s*", "");
                // The marker has to be read before it is stripped from the heading text.
                mandatory = isMandatory(raw);
                heading = clean(raw);
                body.setLength(0);
                examples.clear();
                continue;
            }
            body.append(line).append('\n');
        }
        if (heading != null) {
            add(chunks, file, base, counter + 1, heading, mandatory, body.toString(), examples);
        } else if (!body.toString().isBlank()) {
            add(chunks, file, base, 1, file, false, body.toString(), examples);
        }
        return chunks;
    }

    private static boolean add(List<PolicyChunk> chunks, String file, String base, int counter,
                               String heading, boolean mandatory, String body, List<String> examples) {
        String text = body.strip();
        if (text.isEmpty() && examples.isEmpty()) {
            // A heading with neither prose nor a sample says nothing a reviewer could be held to.
            return false;
        }
        if (!text.isEmpty() && text.length() < 12 && examples.isEmpty()) {
            return false;
        }
        if (text.length() > MAX_CHUNK_CHARS) {
            text = text.substring(0, MAX_CHUNK_CHARS);
        }
        chunks.add(new PolicyChunk("pol-" + base + "-" + counter, file, heading, text, mandatory, examples));
        return true;
    }

    private static boolean isMandatory(String heading) {
        for (String marker : MANDATORY_MARKERS) {
            if (heading.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String heading) {
        String cleaned = heading;
        for (String marker : MANDATORY_MARKERS) {
            cleaned = cleaned.replace(marker, "");
        }
        return cleaned.strip();
    }
}
