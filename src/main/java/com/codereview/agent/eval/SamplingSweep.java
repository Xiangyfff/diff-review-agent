package com.codereview.agent.eval;

import com.codereview.agent.model.TokenLedger;
import com.codereview.agent.review.Comment;
import com.codereview.agent.model.ReviewModels;
import com.codereview.agent.review.ReviewPipeline;
import com.codereview.agent.review.ReviewReport;
import com.codereview.agent.review.Sampling;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-reviews a handful of cases with sampling forced on, to see what triage threw away.
 *
 * <p>Every case named on the command line is one triage skipped. Forcing a 100% sample turns the
 * skipped units into ordinary investigated ones, so a comment here means the gate let a real defect
 * through, and the count over rounds is the miss rate the gate is paying.
 */
public final class SamplingSweep {

    private static final int LINE_TOLERANCE = 5;

    private SamplingSweep() {
    }

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(args.length > 0 ? args[0] : "target/eval-corpus");
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        List<String> only = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            only.add(args[i]);
        }

        CorpusRepository.Corpus built = CorpusRepository.build(corpus);
        String last = built.base();
        List<CorpusRepository.Commit> commits = built.commits();
        java.util.Map<String, String> baseOf = new java.util.HashMap<>();
        for (CorpusRepository.Commit commit : commits) {
            baseOf.put(commit.caseId(), last);
            last = commit.commitId();
        }

        System.out.println("rounds=" + rounds + " cases=" + (only.isEmpty() ? commits.size() : only.size()));

        for (int round = 1; round <= rounds; round++) {
            Sampling sampling = new Sampling(1.0, 100 + round);
            ReviewPipeline pipeline = new ReviewPipeline(ReviewModels.fromEnvironment(),
                    com.codereview.agent.investigate.InvestigateConfig.defaults(),
                    com.codereview.agent.verify.VerifyConfig.defaults())
                    .sampling(sampling);
            int caught = 0;
            int reviewed = 0;
            int comments = 0;
            for (CorpusRepository.Commit commit : commits) {
                if (!only.isEmpty() && !only.contains(commit.caseId())) {
                    continue;
                }
                reviewed++;
                TokenLedger.reset();
                ReviewReport report;
                try {
                    report = pipeline.run(built.workspace(), baseOf.get(commit.caseId()), commit.commitId());
                } catch (RuntimeException e) {
                    System.out.println("  " + commit.caseId() + " -> failed: " + e.getMessage());
                    continue;
                }
                List<Comment> created = report.comments();
                comments += created.size();
                boolean hit = created.stream()
                        .anyMatch(comment -> Math.abs(comment.line() - commit.anchorLine()) <= LINE_TOLERANCE
                                && !comment.evidenceIds().isEmpty());
                if (hit) {
                    caught++;
                }
                TokenLedger.Snapshot used = TokenLedger.snapshot();
                System.out.printf("  r%d %-32s sampled=%d comments=%d hit=%s calls=%d tokens=%d%n",
                        round, commit.caseId(), report.sampling().sampled(), created.size(),
                        hit ? "yes" : "no", used.calls(), used.totalTokens());
                for (Comment comment : created) {
                    String body = comment.body().replace('\n', ' ');
                    System.out.printf("      L%-4d %s%n", comment.line(),
                            body.length() > 150 ? body.substring(0, 150) + "…" : body);
                }
            }
            System.out.printf("round %d: reviewed=%d caught=%d comments=%d%n", round, reviewed, caught, comments);
        }
    }
}
