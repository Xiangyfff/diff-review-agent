package com.codereview.agent.eval;

import com.codereview.agent.review.ReviewPipeline;
import com.codereview.agent.review.ReviewReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private EvalRunner() {
    }

    public static EvalReport run(Path workspace, ReviewPipeline pipeline) throws Exception {
        CorpusRepository.Corpus corpus = CorpusRepository.build(workspace);
        Map<String, MutationCase> cases = new java.util.HashMap<>();
        EvalCorpus.cases().forEach(mutation -> cases.put(mutation.id(), mutation));

        List<CaseOutcome> outcomes = new ArrayList<>();
        String base = corpus.base();
        for (CorpusRepository.Commit commit : corpus.commits()) {
            MutationCase mutation = cases.get(commit.caseId());
            outcomes.addAll(analyse(pipeline, workspace, base, commit.commitId(), mutation,
                    commit.anchorLine()));
            base = commit.commitId();
        }
        return new EvalReport(outcomes, pipeline.modelDescription());
    }

    public static void main(String[] args) throws Exception {
        List<String> options = java.util.Arrays.asList(args);
        Path workspace = Path.of(args.length > 0 && !args[0].startsWith("--")
                ? args[0] : "target/eval-corpus");
        Path output = Path.of(args.length > 1 && !args[1].startsWith("--") ? args[1] : "eval");
        com.codereview.agent.model.ReviewModels models =
                com.codereview.agent.model.ReviewModels.forArguments(options);
        ReviewPipeline pipeline = models == null
                ? ReviewPipeline.offline()
                : new ReviewPipeline(models,
                        com.codereview.agent.investigate.InvestigateConfig.defaults(),
                        com.codereview.agent.verify.VerifyConfig.defaults());
        if (options.contains("--no-policy")) {
            pipeline.policyRetrieval(false);
        }
        EvalReport report = run(workspace, pipeline);
        Files.createDirectories(output);
        Files.writeString(output.resolve("latest-report.md"), report.renderMarkdown(),
                StandardCharsets.UTF_8);
        Files.writeString(output.resolve("latest-report.json"), report.renderJson(),
                StandardCharsets.UTF_8);
        System.out.println(report.summary());
        System.out.println("models: " + report.models());
        System.out.println("written to " + output.toAbsolutePath());
    }

    private static List<CaseOutcome> analyse(ReviewPipeline pipeline, Path root, String base,
            String head, MutationCase mutation, int anchorLine) throws Exception {
        ReviewReport report = pipeline.run(root, base, head);
        String tier = report.units().isEmpty() ? "SKIP" : report.units().get(0).tier();
        boolean lineMatched = report.comments().stream()
                .anyMatch(comment -> Math.abs(comment.line() - anchorLine)
                        <= EvalReport.LINE_TOLERANCE);
        boolean evidenceBacked = report.comments().stream()
                .anyMatch(comment -> Math.abs(comment.line() - anchorLine)
                        <= EvalReport.LINE_TOLERANCE && !comment.evidenceIds().isEmpty());
        for (com.codereview.agent.review.UnitOutcome unit : report.units()) {
            if (unit.findings() == 0) {
                System.out.println("EMPTY " + mutation.id() + " | " + unit.unitKey() + " | "
                        + unit.outcome() + " | calls=" + unit.toolCalls() + " | " + unit.abandoned());
            }
        }
        return List.of(new CaseOutcome(mutation.id(), mutation.defective(), mutation.kind(),
                report.units().size(), tier, report.findings(), report.assertions(),
                report.questions(), report.dropped(), report.merged(),
                report.invalidRetractions(), lineMatched, anchorLine, evidenceBacked));
    }
}
