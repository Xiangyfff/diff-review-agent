package com.codereview.agent.eval;

import com.codereview.agent.review.ReviewPipeline;
import com.codereview.agent.review.ReviewReport;
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
 * Runs the whole pipeline over a corpus of injected changes and records what came out.
 *
 * <p>The corpus is materialised as a real git repository, one commit per mutation, because the
 * pipeline only accepts a repository and two revisions: measuring anything narrower than what
 * production actually runs would be measuring a different system.
 */
public final class EvalRunner {

    private static final ReviewPipeline PIPELINE = ReviewPipeline.offline();

    private EvalRunner() {
    }

    public static EvalReport run(Path workspace) throws Exception {
        prepare(workspace);
        try (Git git = Git.init().setDirectory(workspace.toFile()).call()) {
            Map<String, String> files = EvalCorpus.baseFiles();
            for (Map.Entry<String, String> file : files.entrySet()) {
                write(workspace, file.getKey(), file.getValue());
            }
            commit(git, "base");
            String base = head(git);

            List<CaseOutcome> outcomes = new ArrayList<>();
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
                String head = head(git);

                outcomes.addAll(analyse(workspace, base, head, mutation, anchorLine));
                base = head;
            }
            return new EvalReport(outcomes);
        }
    }

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args.length > 0 ? args[0] : "target/eval-corpus");
        Path output = Path.of(args.length > 1 ? args[1] : "eval");
        EvalReport report = run(workspace);
        Files.createDirectories(output);
        Files.writeString(output.resolve("latest-report.md"), report.renderMarkdown(),
                StandardCharsets.UTF_8);
        Files.writeString(output.resolve("latest-report.json"), report.renderJson(),
                StandardCharsets.UTF_8);
        System.out.println(report.summary());
        System.out.println("written to " + output.toAbsolutePath());
    }

    private static List<CaseOutcome> analyse(Path root, String base, String head,
            MutationCase mutation, int anchorLine) throws Exception {
        ReviewReport report = PIPELINE.run(root, base, head);
        String tier = report.units().isEmpty() ? "SKIP" : report.units().get(0).tier();
        boolean lineMatched = report.comments().stream()
                .anyMatch(comment -> Math.abs(comment.line() - anchorLine)
                        <= EvalReport.LINE_TOLERANCE);
        return List.of(new CaseOutcome(mutation.id(), mutation.defective(), mutation.kind(),
                report.units().size(), tier, report.findings(), report.assertions(),
                report.questions(), report.dropped(), report.invalidRetractions(), lineMatched,
                anchorLine));
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

    private static int count(String text, String needle) {
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

    private static int lineOf(String text, String anchor) {
        int index = text.indexOf(anchor);
        if (index < 0) {
            throw new IllegalStateException("anchor is not present after the mutation: " + anchor);
        }
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
