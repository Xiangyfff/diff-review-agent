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
 * <p>A heading may carry three markers:
 *
 * <ul>
 *   <li>{@code [强制]} / {@code [mandatory]} — the document's own statement that any change
 *       touching the section has to be checked. Only these clauses may raise a tier.</li>
 *   <li>{@code [参考]} / {@code [advisory]} — background. The clause can be quoted, but it may
 *       not raise a tier and may not exempt anything.</li>
 *   <li>{@code [允许]} / {@code [allows]} — an allowance. It names the rules it exempts and the
 *       paths it covers, and carries an example; all three have to match before it is used.</li>
 * </ul>
 *
 * <p>Directives inside the body use a line of their own:
 * {@code 豁免规则：NULL-CHECK-REMOVED} and {@code 适用范围：src/main/java/**}. English spellings
 * {@code exempts:} and {@code scope:} work too.
 */
public final class PolicyLibrary {

    public static final int MAX_CHUNK_CHARS = 1500;
    private static final String[] MANDATORY_MARKERS = {"[强制]", "[mandatory]"};
    private static final String[] ADVISORY_MARKERS = {"[参考]", "[advisory]"};
    private static final String[] ALLOWANCE_MARKERS = {"[允许]", "[allows]"};
    private static final String[] EXEMPT_KEYS = {"豁免规则", "exempts"};
    private static final String[] SCOPE_KEYS = {"适用范围", "范围", "scope"};

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
        boolean advisory = false;
        boolean allowance = false;
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
                if (heading != null && add(chunks, file, base, counter + 1, heading, mandatory, advisory,
                        allowance, body.toString(), examples)) {
                    counter++;
                }
                String raw = line.replaceAll("^#+\\s*", "");
                // The markers have to be read before they are stripped from the heading text.
                mandatory = hasAny(raw, MANDATORY_MARKERS);
                advisory = hasAny(raw, ADVISORY_MARKERS);
                allowance = hasAny(raw, ALLOWANCE_MARKERS);
                if (allowance) {
                    // An allowance is never a requirement, even if both markers were written.
                    mandatory = false;
                }
                heading = clean(raw);
                body.setLength(0);
                examples.clear();
                continue;
            }
            body.append(line).append('\n');
        }
        if (heading != null) {
            add(chunks, file, base, counter + 1, heading, mandatory, advisory, allowance,
                    body.toString(), examples);
        } else if (!body.toString().isBlank()) {
            add(chunks, file, base, 1, file, false, false, false, body.toString(), examples);
        }
        return chunks;
    }

    private static boolean add(List<PolicyChunk> chunks, String file, String base, int counter,
                               String heading, boolean mandatory, boolean advisory, boolean allowance,
                               String body, List<String> examples) {
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
        List<String> exemptedRules = allowance ? directive(text, EXEMPT_KEYS) : List.of();
        List<String> scope = allowance ? directive(text, SCOPE_KEYS) : List.of();
        chunks.add(new PolicyChunk("pol-" + base + "-" + counter, file, heading, text, mandatory,
                advisory, allowance, exemptedRules, scope, examples));
        return true;
    }

    /** Values of a {@code key: a, b} line, in document order. */
    private static List<String> directive(String text, String[] keys) {
        List<String> values = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            for (String key : keys) {
                if (!stripped.startsWith(key)) {
                    continue;
                }
                String rest = stripped.substring(key.length()).strip();
                if (!(rest.startsWith(":") || rest.startsWith("："))) {
                    continue;
                }
                rest = rest.substring(1).strip();
                for (String value : rest.split("[,，、;；]+")) {
                    if (!value.isBlank()) {
                        values.add(value.strip());
                    }
                }
            }
        }
        return values;
    }

    private static boolean hasAny(String heading, String[] markers) {
        for (String marker : markers) {
            if (heading.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String heading) {
        String cleaned = heading;
        for (String[] markers : new String[][]{MANDATORY_MARKERS, ADVISORY_MARKERS, ALLOWANCE_MARKERS}) {
            for (String marker : markers) {
                cleaned = cleaned.replace(marker, "");
            }
        }
        return cleaned.strip();
    }
}
