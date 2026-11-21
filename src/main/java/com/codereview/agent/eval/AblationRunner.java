package com.codereview.agent.eval;

import com.codereview.agent.model.TokenLedger;
import com.codereview.agent.review.Depth;
import com.codereview.agent.review.ReviewPipeline;
import com.codereview.agent.review.ReviewReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures what each stage is worth: the same corpus, run three times, stopping one stage earlier
 * each time.
 *
 * <p>Without this table "the design helps" is an opinion. With it, the claim is a row: how many
 * injected defects were caught and how many clean changes were interrupted, at each depth.
 */
public final class AblationRunner {

    private static final int LINE_TOLERANCE = EvalReport.LINE_TOLERANCE;

    private AblationRunner() {
    }

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args.length > 0 && !args[0].startsWith("--") ? args[0]
                : "target/eval-corpus");
        Path output = Path.of(args.length > 1 && !args[1].startsWith("--") ? args[1] : "eval");
        boolean real = List.of(args).contains("--real");

        ReviewPipeline pipeline = real
                ? new ReviewPipeline(com.codereview.agent.model.ReviewModels.fromEnvironment(),
                        com.codereview.agent.investigate.InvestigateConfig.defaults(),
                        com.codereview.agent.verify.VerifyConfig.defaults())
                : ReviewPipeline.offline();

        CorpusRepository.Corpus corpus = CorpusRepository.build(workspace);
        List<Row> rows = new ArrayList<>();
        for (Depth depth : Depth.values()) {
            rows.add(measure(pipeline, workspace, corpus, depth));
        }
        String markdown = render(rows, pipeline.modelDescription());
        Files.createDirectories(output);
        Files.writeString(output.resolve("latest-ablation.md"), markdown, StandardCharsets.UTF_8);
        System.out.println(markdown);
    }

    private static Row measure(ReviewPipeline pipeline, Path workspace, CorpusRepository.Corpus corpus,
            Depth depth) throws IOException {
        List<MutationCase> cases = EvalCorpus.cases();
        TokenLedger.reset();
        int comments = 0;
        int findings = 0;
        int caught = 0;
        int defective = 0;
        int clean = 0;
        int cleanFlagged = 0;
        String base = corpus.base();
        for (CorpusRepository.Commit commit : corpus.commits()) {
            MutationCase mutation = cases.stream().filter(c -> c.id().equals(commit.caseId()))
                    .findFirst().orElseThrow();
            ReviewReport report = pipeline.run(workspace, base, commit.commitId(), depth);
            comments += report.comments().size();
            findings += report.findings();
            boolean flagged = !report.comments().isEmpty();
            if (mutation.defective()) {
                defective++;
                boolean near = report.comments().stream()
                        .anyMatch(comment -> Math.abs(comment.line() - commit.anchorLine()) <= LINE_TOLERANCE);
                if (near) {
                    caught++;
                }
            } else {
                clean++;
                if (flagged) {
                    cleanFlagged++;
                }
            }
            base = commit.commitId();
        }
        TokenLedger.Snapshot tokens = TokenLedger.snapshot();
        return new Row(depth, defective, caught, clean, cleanFlagged, comments, findings,
                tokens.calls(), tokens.totalTokens());
    }

    private static String render(List<Row> rows, String models) {
        StringBuilder text = new StringBuilder();
        text.append("# Ablation\n\n");
        text.append("Same corpus, same models (").append(models).append("), one stage removed per row.\n\n");
        text.append("| depth | defects caught | clean changes flagged | comments | findings | model calls | tokens |\n");
        text.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (Row row : rows) {
            text.append("| ").append(row.depth())
                    .append(" | ").append(row.caught()).append("/").append(row.defective())
                    .append(" (").append(percent(row.caught(), row.defective())).append(")")
                    .append(" | ").append(row.cleanFlagged()).append("/").append(row.clean())
                    .append(" (").append(percent(row.cleanFlagged(), row.clean())).append(")")
                    .append(" | ").append(row.comments())
                    .append(" | ").append(row.findings())
                    .append(" | ").append(row.calls())
                    .append(" | ").append(row.tokens())
                    .append(" |\n");
        }
        return text.toString();
    }

    private static String percent(int part, int whole) {
        if (whole == 0) {
            return "-";
        }
        return Math.round(100.0 * part / whole) + "%";
    }

    /** One row of the table: a depth, and what it cost and caught. */
    record Row(Depth depth, int defective, int caught, int clean, int cleanFlagged, int comments,
            int findings, long calls, long tokens) {
    }
}
