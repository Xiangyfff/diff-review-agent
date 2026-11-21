package com.codereview.agent.eval;

import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Materialises the mutation corpus as a real git repository: one commit for the base files, then one
 * commit per mutation, each building on the previous one.
 *
 * <p>Shared by the evaluation harness and the demo page, so both look at exactly the same history:
 * a mutation that only exists inside the harness would be measured but never shown.
 */
public final class CorpusRepository {

    /** One mutation, already committed. */
    public record Commit(String caseId, String commitId, int anchorLine) {
    }

    public record Corpus(Path workspace, String base, List<Commit> commits) {

        public String commitOf(String caseId) {
            return commits.stream()
                    .filter(commit -> commit.caseId().equals(caseId))
                    .map(Commit::commitId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown case: " + caseId));
        }
    }

    private CorpusRepository() {
    }

    public static Corpus build(Path workspace) throws Exception {
        prepare(workspace);
        try (Git git = Git.init().setDirectory(workspace.toFile()).call()) {
            for (Map.Entry<String, String> file : EvalCorpus.baseFiles().entrySet()) {
                write(workspace, file.getKey(), file.getValue());
            }
            commit(git, "base");
            String base = head(git);

            List<Commit> commits = new ArrayList<>();
            for (MutationCase mutation : EvalCorpus.cases()) {
                String current = Files.readString(workspace.resolve(mutation.path()),
                        StandardCharsets.UTF_8);
                int occurrences = count(current, mutation.search());
                if (occurrences != 1) {
                    throw new IllegalStateException("case " + mutation.id() + " expects exactly one "
                            + "occurrence of its search text but found " + occurrences);
                }
                String mutated = current.replace(mutation.search(), mutation.replace());
                write(workspace, mutation.path(), mutated);
                int anchorLine = mutation.anchor() == null ? 0 : lineOf(mutated, mutation.anchor());
                commit(git, mutation.id());
                commits.add(new Commit(mutation.id(), head(git), anchorLine));
            }
            return new Corpus(workspace, base, List.copyOf(commits));
        }
    }

    private static void prepare(Path workspace) throws IOException {
        if (Files.exists(workspace)) {
            try (var walk = Files.walk(workspace)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        deleteQuietly(path);
                    } catch (IOException failure) {
                        throw new IllegalStateException("cannot clean " + path, failure);
                    }
                });
            }
        }
        Files.createDirectories(workspace);
    }

    /**
     * Git objects are marked read-only on Windows, which makes a plain delete fail with
     * AccessDeniedException. Clearing the attribute first is the cheapest way to reuse a corpus
     * directory between runs.
     */
    private static void deleteQuietly(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        path.toFile().setWritable(true);
        if (Files.isDirectory(path)) {
            path.toFile().setReadable(true);
            path.toFile().setExecutable(true);
        }
        Files.deleteIfExists(path);
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAllowEmpty(false).call();
    }

    private static String head(Git git) throws Exception {
        return git.getRepository().resolve("HEAD").name();
    }

    static int count(String text, String needle) {
        int occurrences = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                return occurrences;
            }
            occurrences++;
            from = index + 1;
        }
    }

    static int lineOf(String text, String needle) {
        int index = text.indexOf(needle);
        if (index < 0) {
            return 0;
        }
        return (int) text.substring(0, index).lines().count() + 1;
    }
}
