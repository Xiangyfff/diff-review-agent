package com.codereview.agent.review;

import com.codereview.agent.investigate.Evidence;
import com.codereview.agent.investigate.Finding;
import com.codereview.agent.investigate.InvestigateConfig;
import com.codereview.agent.investigate.InvestigationModel;
import com.codereview.agent.investigate.InvestigationResult;
import com.codereview.agent.investigate.InvestigationRunner;
import com.codereview.agent.investigate.InvestigationTask;
import com.codereview.agent.investigate.MockInvestigationModel;
import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.Toolbox;
import com.codereview.agent.triage.TriageService;
import com.codereview.agent.triage.index.IndexBuilder;
import com.codereview.agent.triage.index.SymbolIndex;
import com.codereview.agent.triage.parse.SourceParser;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.plan.TriagePlan;
import com.codereview.agent.verify.MockVerifier;
import com.codereview.agent.verify.VerificationRequest;
import com.codereview.agent.verify.VerifyConfig;
import com.codereview.agent.verify.VerifyResult;
import com.codereview.agent.verify.Verifier;
import com.codereview.agent.verify.VerifierRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The three stages wired together: triage decides what to look at, investigation gathers evidence,
 * verification throws conclusions away unless the evidence holds.
 *
 * <p>Kept separate from the stages themselves so each can still be exercised alone, and so the
 * evaluation harness measures the same path a user would run rather than a parallel test-only path.
 */
public final class ReviewPipeline {

    private final TriageService triage;
    private final InvestigationModel investigationModel;
    private final Verifier verifier;
    private final InvestigateConfig investigateConfig;
    private final VerifyConfig verifyConfig;

    public ReviewPipeline(TriageService triage, InvestigationModel investigationModel,
            Verifier verifier, InvestigateConfig investigateConfig, VerifyConfig verifyConfig) {
        this.triage = triage;
        this.investigationModel = investigationModel;
        this.verifier = verifier;
        this.investigateConfig = investigateConfig;
        this.verifyConfig = verifyConfig;
    }

    /**
     * The offline configuration: everything deterministic, nothing touches the network.
     */
    public static ReviewPipeline offline() {
        return new ReviewPipeline(TriageService.offline(), new MockInvestigationModel(),
                new MockVerifier(), InvestigateConfig.defaults(), VerifyConfig.defaults());
    }

    public ReviewReport run(Path repository, String baseRevision, String headRevision)
            throws IOException {
        TriagePlan plan = triage.triage(repository, baseRevision, headRevision);

        // The index is rebuilt here instead of being handed over by triage: triage owns its own
        // copy for prompts, and tying the two stages together through a shared mutable index would
        // make either stage harder to run alone. Building it costs a filesystem walk.
        SymbolIndex index = new IndexBuilder(new SourceParser()).build(repository).index();
        Toolbox toolbox = Toolbox.standard(new RepoAccess(repository, index));
        InvestigationRunner investigator = new InvestigationRunner(investigationModel, toolbox,
                investigateConfig);
        VerifierRunner verifierRunner = new VerifierRunner(verifier, toolbox, verifyConfig);

        List<UnitOutcome> outcomes = new ArrayList<>();
        List<Comment> comments = new ArrayList<>();
        int findings = 0;
        int dropped = 0;
        int invalidRetractions = 0;
        int merged = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (PlannedUnit unit : plan.scheduled()) {
            InvestigationResult result = investigator.investigate(taskOf(unit));
            findings += result.findings().size();
            int verifyCalls = 0;
            for (Finding finding : result.findings()) {
                List<Evidence> cited = result.evidence().stream()
                        .filter(evidence -> finding.evidenceIds().contains(evidence.id()))
                        .toList();
                VerifyResult verdict = verifierRunner.review(new VerificationRequest(finding, cited,
                        unit.unit().textBefore(), unit.unit().textAfter()));
                verifyCalls += verdict.toolCalls();
                if (verdict.invalid()) {
                    invalidRetractions++;
                }
                if (verdict.disposition() == VerifyResult.Disposition.DROPPED) {
                    dropped++;
                    continue;
                }
                // Two questions may end in the same statement at the same line. One comment per
                // place and claim, otherwise the report repeats itself and the counts inflate.
                String key = finding.file() + "|" + finding.line() + "|" + finding.statement();
                if (!seen.add(key)) {
                    merged++;
                    continue;
                }
                comments.add(new Comment(finding.file(), finding.line(),
                        unit.category().name(), statusOf(verdict), finding.statement(),
                        finding.evidenceIds()));
            }
            outcomes.add(new UnitOutcome(unit.unit().file(), unit.unit().key(),
                    unit.tier().name(), unit.category().name(), result.outcome().name(),
                    result.toolCalls(), result.findings().size(), verifyCalls));
        }
        return new ReviewReport(baseRevision, headRevision, plan.stats(), outcomes, comments,
                findings, dropped, invalidRetractions, merged);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: ReviewPipeline <repository> <base> <head> [output-directory]");
            System.exit(2);
        }
        Path repository = Path.of(args[0]);
        Path output = Path.of(args.length > 3 ? args[3] : "review");
        ReviewReport report = ReviewPipeline.offline().run(repository, args[1], args[2]);
        Files.createDirectories(output);
        Files.writeString(output.resolve("review.md"), report.renderMarkdown(),
                StandardCharsets.UTF_8);
        Files.writeString(output.resolve("review.json"), report.renderJson(),
                StandardCharsets.UTF_8);
        System.out.println(report.units().size() + " units, " + report.comments().size()
                + " comments (" + report.assertions() + " assertions, " + report.questions()
                + " questions), " + report.dropped() + " findings rejected by the verifier, "
                + report.invalidRetractions() + " retractions without evidence, "
                + report.merged() + " duplicates merged");
        System.out.println("written to " + output.toAbsolutePath());
    }

    private static Comment.Status statusOf(VerifyResult verdict) {
        return verdict.disposition() == VerifyResult.Disposition.QUESTION
                ? Comment.Status.QUESTION
                : Comment.Status.ASSERTION;
    }

    private static InvestigationTask taskOf(PlannedUnit unit) {
        return new InvestigationTask(
                unit.unit().key(),
                unit.unit().file(),
                unit.unit().after() == null ? 1 : unit.unit().after().startLine(),
                unit.unit().textBefore(),
                unit.unit().textAfter(),
                unit.changes().stream().map(change -> change.describe()).toList(),
                unit.questions().stream().map(question -> question.text()).toList());
    }
}
