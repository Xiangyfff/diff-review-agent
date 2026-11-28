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
import com.codereview.agent.triage.TriageConfig;
import com.codereview.agent.triage.TriageService;
import com.codereview.agent.triage.index.IndexBuilder;
import com.codereview.agent.triage.index.SymbolIndex;
import com.codereview.agent.triage.parse.SourceParser;
import com.codereview.agent.triage.model.FindingQuestion;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.policy.PolicyLibrary;
import com.codereview.agent.triage.policy.PolicyIndex;
import com.codereview.agent.triage.policy.PolicyChunk;
import com.codereview.agent.triage.policy.OllamaEmbedder;
import com.codereview.agent.triage.rules.RuleCatalog;
import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.tier.Tier;
import com.codereview.agent.triage.plan.TriagePlan;
import com.codereview.agent.verify.MockVerifier;
import com.codereview.agent.verify.Verdict;
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
    private Sampling sampling = Sampling.OFF;
    private boolean policyRetrieval = true;
    private com.codereview.agent.triage.model.AuthorIntent authorIntent =
            com.codereview.agent.triage.model.AuthorIntent.empty();
    private final VerifyConfig verifyConfig;
    private final ModelFactory models;
    private final String modelDescription;

    /** Mutable on purpose: the demo sets it once before running and nothing else touches it. */
    /** The author's own description of the change, handed to the intent scan as a claim. */
    public ReviewPipeline authorIntent(com.codereview.agent.triage.model.AuthorIntent intent) {
        this.authorIntent = intent == null ? com.codereview.agent.triage.model.AuthorIntent.empty() : intent;
        return this;
    }

    /** What the author said about this change, if anything was supplied. */
    public com.codereview.agent.triage.model.AuthorIntent authorIntent() {
        return authorIntent;
    }

    /** Turn policy retrieval off; used to measure what the policy documents contribute. */
    public ReviewPipeline policyRetrieval(boolean enabled) {
        this.policyRetrieval = enabled;
        return this;
    }

    public ReviewPipeline sampling(Sampling sampling) {
        this.sampling = sampling == null ? Sampling.OFF : sampling;
        return this;
    }

    public ReviewPipeline(TriageService triage, InvestigationModel investigationModel,
            Verifier verifier, InvestigateConfig investigateConfig, VerifyConfig verifyConfig) {
        this.triage = triage;
        this.investigationModel = investigationModel;
        this.verifier = verifier;
        this.investigateConfig = investigateConfig;
        this.verifyConfig = verifyConfig;
        this.models = null;
        this.modelDescription = "deterministic offline stand-ins";
    }

    /**
     * A pipeline whose stages all speak to whatever the factory builds. The investigation model is
     * requested inside {@link #run} because it needs the toolbox, and the toolbox needs the repository.
     */
    public ReviewPipeline(ModelFactory models, InvestigateConfig investigateConfig,
            VerifyConfig verifyConfig) {
        this.triage = new TriageService(models.triage(), TriageConfig.defaults());
        this.investigationModel = null;
        this.verifier = models.verifier();
        this.investigateConfig = investigateConfig;
        this.verifyConfig = verifyConfig;
        this.models = models;
        this.modelDescription = models.describe();
    }

    /**
     * The offline configuration: everything deterministic, nothing touches the network.
     */
    public static ReviewPipeline offline() {
        return new ReviewPipeline(TriageService.offline(), new MockInvestigationModel(),
                new MockVerifier(), InvestigateConfig.defaults(), VerifyConfig.defaults());
    }

    /** Which models this pipeline talks to, for the report header. */
    public String modelDescription() {
        return modelDescription;
    }

    public ReviewReport run(Path repository, String baseRevision, String headRevision)
            throws IOException {
        return run(repository, baseRevision, headRevision, Depth.FULL);
    }

    /**
     * Runs the pipeline down to the given depth. Stopping early is not a debugging switch: the three
     * depths are the rows of the ablation table, which is the only evidence that each stage earns
     * its cost.
     */
    public ReviewReport run(Path repository, String baseRevision, String headRevision, Depth depth)
            throws IOException {
        return run(repository, baseRevision, headRevision, depth, List.of());
    }

    /**
     * @param includePaths path prefixes screening must not drop; the manual way back in
     */
    public ReviewReport run(Path repository, String baseRevision, String headRevision, Depth depth,
            List<String> includePaths) throws IOException {
        TriageService effectiveTriage = policyRetrieval ? triage : triage.withoutPolicies();
        TriagePlan plan = effectiveTriage.withAuthorIntent(authorIntent).including(includePaths)
                .triage(repository, baseRevision, headRevision);
        List<PlannedUnit> scheduled = plan.scheduled();
        // Sampling needs the tools, so a triage-only run measures nothing here and says so.
        List<PlannedUnit> sampled = depth == Depth.TRIAGE_ONLY
                ? List.of() : sampling.pick(plan.units(), scheduled);

        List<UnitOutcome> outcomes = new ArrayList<>();
        List<Comment> comments = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Tally tally = new Tally();

        if (depth == Depth.TRIAGE_ONLY) {
            // The baseline a reviewer without any tools would give: every question triage raised is
            // published as-is. Nothing is read, so nothing can be checked.
            for (PlannedUnit unit : scheduled) {
                for (FindingQuestion question : unit.questions()) {
                    String key = unit.unit().file() + "|" + unit.unit().startLine() + "|"
                            + question.text();
                    if (!seen.add(key)) {
                        tally.merged++;
                        continue;
                    }
                    comments.add(new Comment(unit.unit().file(), unit.unit().startLine(),
                            unit.category().name(), Comment.Status.QUESTION, question.text(),
                            List.of()));
                }
                outcomes.add(new UnitOutcome(unit.unit().file(), unit.unit().key(),
                        unit.tier().name(), unit.category().name(), "TRIAGE_ONLY", 0, 0, 0,
                        List.of(), false, policyIdsOf(unit)));
            }
            return report(plan, outcomes, comments, tally, Sampling.Summary.none());
        }

        // The index is rebuilt here instead of being handed over by triage: triage owns its own
        // copy for prompts, and tying the two stages together through a shared mutable index would
        // make either stage harder to run alone. Building it costs a filesystem walk.
        SymbolIndex index = new IndexBuilder(new SourceParser()).build(repository).index();
        PolicyIndex policies = policyIndex(repository);
        Toolbox toolbox = Toolbox.standard(new RepoAccess(repository, index), policies);
        java.util.Map<String, String> clauseTexts = new java.util.LinkedHashMap<>();
        java.util.Map<String, PolicyChunk> clauseChunks = new java.util.LinkedHashMap<>();
        for (PolicyChunk chunk : policies.chunks()) {
            // What read_policy returns is what the verifier trusts: the prose and the examples,
            // so a fix copied from the clause's own 正例 is not treated as an invented name.
            clauseTexts.put(chunk.id(), chunk.fullText());
            clauseChunks.put(chunk.id(), chunk);
        }
        InvestigationModel investigatorModel = models == null ? investigationModel
                : models.investigation(toolbox);
        InvestigationRunner investigator = new InvestigationRunner(investigatorModel, toolbox,
                investigateConfig, clauseTexts, clauseChunks);
        VerifierRunner verifierRunner = depth == Depth.FULL
                ? new VerifierRunner(verifier, toolbox, verifyConfig) : null;

        for (PlannedUnit unit : scheduled) {
            // Only a mandatory unit carries seeded questions. Judge-tier units and samples open
            // with an empty queue so the investigation raises questions from the code it reads.
            List<FindingQuestion> seeds = unit.tier() == Tier.MUST_INVESTIGATE
                    ? unit.questions() : List.of();
            outcomes.add(investigateAndComment(unit, seeds, investigator, verifierRunner,
                    false, seen, comments, tally, clauseTexts));
        }

        int sampledWithFindings = 0;
        for (PlannedUnit unit : sampled) {
            UnitOutcome outcome = investigateAndComment(unit, List.of(), investigator,
                    verifierRunner, true, seen, comments, tally, clauseTexts);
            outcomes.add(outcome);
            if (outcome.findings() > 0) {
                sampledWithFindings++;
            }
        }

        // A unit the policy documents explicitly allowed is reported, but not as a problem: it is
        // not investigated and produces no comment, and its tier says so.
        for (PlannedUnit unit : plan.units()) {
            if (unit.exempted()) {
                outcomes.add(new UnitOutcome(unit.unit().file(), unit.unit().key(), "EXEMPTED",
                        unit.category().name(), "EXEMPTED", 0, 0, 0, List.of(), false,
                        policyIdsOf(unit)));
            }
        }

        Sampling.Summary summary = new Sampling.Summary(sampling.rate(), sampling.seed(),
                plan.units().size() - scheduled.size(), sampled.size(), sampledWithFindings);
        return report(plan, outcomes, comments, tally, summary);
    }

    /**
     * One unit, from task to comment. Shared by the scheduled units and the sampled ones so the two
     * cannot drift into different verdict handling.
     */
    private UnitOutcome investigateAndComment(PlannedUnit unit, List<FindingQuestion> questions,
            InvestigationRunner investigator, VerifierRunner verifierRunner, boolean sampled,
            java.util.Set<String> seen, List<Comment> comments, Tally tally,
            java.util.Map<String, String> clauseTexts) {
        InvestigationResult result = investigator.investigate(taskOf(unit, questions));
        tally.findings += result.findings().size();
        int verifyCalls = 0;
        java.util.List<String> exemptionClauseIds = new ArrayList<>();
        for (Finding finding : result.findings()) {
            VerifyResult verdict;
            if (verifierRunner == null) {
                verdict = VerifyResult.of(finding, new Verdict.Keep("没有开启复核阶段"), 0);
            } else {
                List<Evidence> cited = result.evidence().stream()
                        .filter(evidence -> finding.evidenceIds().contains(evidence.id()))
                        .toList();
                verdict = verifierRunner.review(new VerificationRequest(finding, cited,
                        unit.unit().textBefore(), unit.unit().textAfter(), result.attempts()));
                verifyCalls += verdict.toolCalls();
                tally.verified++;
                if (verdict.disposition() == VerifyResult.Disposition.QUESTION) {
                    tally.downgraded++;
                }
            }
            if (verdict.invalid()) {
                tally.invalidRetractions++;
            }
            if (verdict.disposition() == VerifyResult.Disposition.DROPPED) {
                tally.dropped++;
                continue;
            }
            // Two questions may end in the same statement at the same line. One comment per place
            // and claim, otherwise the report repeats itself and the counts inflate.
            String key = finding.file() + "|" + finding.line() + "|" + finding.statement();
            if (!seen.add(key)) {
                tally.merged++;
                continue;
            }
            comments.add(new Comment(finding.file(), finding.line(),
                    unit.category().name(), statusOf(verdict), finding.statement(),
                    finding.evidenceIds()));
        }
        // A deep-dive exemption is a claim too — "the clause allows this" — so it goes through the
        // same critic as a finding. Only an unrefuted claim stays an exemption; a refuted one turns
        // back into a question for the author, quoting what the critic found.
        boolean exemptionUpheld = true;
        for (InvestigationResult.Exemption exemption : result.exemptions()) {
            exemptionClauseIds.add(exemption.clauseId());
            if (verifierRunner == null) {
                continue;
            }
            Finding claim = new Finding(unit.unit().key(), unit.unit().file(), exemption.line(),
                    "条款 " + exemption.clauseId() + " 允许这处改动：" + exemption.reason(),
                    exemption.evidenceIds(), Finding.CONFIRM);
            List<Evidence> cited = result.evidence().stream()
                    .filter(evidence -> exemption.evidenceIds().contains(evidence.id()))
                    .toList();
            VerifyResult verdict = verifierRunner.review(new VerificationRequest(claim, cited,
                    unit.unit().textBefore(), unit.unit().textAfter(), result.attempts(),
                    exemption.clauseId(), clauseTexts.getOrDefault(exemption.clauseId(), ""),
                    exemption.quote(), exemption.reason()));
            verifyCalls += verdict.toolCalls();
            tally.verified++;
            if (verdict.invalid()) {
                tally.invalidRetractions++;
            }
            if (verdict.disposition() != VerifyResult.Disposition.ASSERTION) {
                exemptionUpheld = false;
                comments.add(new Comment(unit.unit().file(), exemption.line(),
                        unit.category().name(), Comment.Status.QUESTION,
                        "调查员认为条款 " + exemption.clauseId() + " 允许这处改动，但复核没有确认："
                                + verdict.verdict().reason()
                                + "。请确认这处改动确实落在条款允许的范围和示例之内。",
                        exemption.evidenceIds()));
            }
        }
        // A unit the rules insist on investigating must not end up silent just because the model
        // gave up. Turn that outcome into a question the author can answer, and say plainly that
        // the evidence never arrived.
        if (result.findings().isEmpty() && result.exemptions().isEmpty()
                && unit.tier() == Tier.MUST_INVESTIGATE) {
            comments.add(unverified(unit));
        }
        List<String> abandonReasons = result.abandoned().stream()
                .map(abandoned -> abandoned.question() + " -> " + abandoned.reason())
                .toList();
        List<String> policies = new ArrayList<>(policyIdsOf(unit));
        for (String clauseId : exemptionClauseIds) {
            if (!policies.contains(clauseId)) {
                policies.add(clauseId);
            }
        }
        String outcome = result.outcome().name();
        if (result.exempted() && !exemptionUpheld) {
            outcome = InvestigationResult.Outcome.CONCLUDED.name();
        }
        return new UnitOutcome(unit.unit().file(), unit.unit().key(), unit.tier().name(),
                unit.category().name(), outcome, result.toolCalls(),
                result.findings().size(), verifyCalls, abandonReasons, sampled, policies);
    }

    /** The policy clauses the triage stage attached to this unit, for the report and the page. */
    private static List<String> policyIdsOf(PlannedUnit unit) {
        String facts = unit.facts();
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("pol-[A-Za-z0-9-]+").matcher(facts);
        while (matcher.find()) {
            if (!ids.contains(matcher.group())) {
                ids.add(matcher.group());
            }
        }
        return ids;
    }

    /**
     * The policy index the investigator's two policy tools search. Absent documents or an
     * unreachable embedding endpoint both mean "no policy tools this run", never a failed review.
     */
    private static PolicyIndex policyIndex(Path repository) {
        PolicyLibrary library = PolicyLibrary.load(repository.resolve("docs/policies"));
        if (library.isEmpty()) {
            return PolicyIndex.empty();
        }
        try {
            return PolicyIndex.build(library, OllamaEmbedder.fromEnvironment(),
                    PolicyIndex.Scoring.SAMPLES);
        } catch (RuntimeException unreachable) {
            return PolicyIndex.empty();
        }
    }

    private ReviewReport report(TriagePlan plan, List<UnitOutcome> outcomes, List<Comment> comments,
            Tally tally, Sampling.Summary summary) {
        return new ReviewReport(plan.baseRevision(), plan.headRevision(), plan.stats(), outcomes,
                comments, tally.findings, tally.dropped, tally.invalidRetractions, tally.merged,
                plan.screenedOut(), tally.verified, tally.downgraded, summary);
    }

    private static final class Tally {
        private int findings;
        private int dropped;
        private int invalidRetractions;
        private int merged;
        private int verified;
        private int downgraded;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: ReviewPipeline <repository> <base> <head> [output-directory]"
                    + " [--real | --ollama] [--pr owner/name#123 | --description <file>]");
            System.exit(2);
        }
        Path repository = Path.of(args[0]);
        Path output = Path.of(args.length > 3 && !args[3].startsWith("--") ? args[3] : "review");
        com.codereview.agent.model.ReviewModels models =
                com.codereview.agent.model.ReviewModels.forArguments(java.util.Arrays.asList(args));
        ReviewPipeline pipeline = models == null
                ? ReviewPipeline.offline()
                : new ReviewPipeline(models, InvestigateConfig.defaults(), VerifyConfig.defaults());
        for (int i = 0; i < args.length; i++) {
            if ("--description".equals(args[i]) && i + 1 < args.length) {
                Path file = Path.of(args[i + 1]);
                pipeline.authorIntent(com.codereview.agent.triage.model.AuthorIntent
                        .fromText(Files.readString(file, StandardCharsets.UTF_8), file.toString()));
            } else if ("--pr".equals(args[i]) && i + 1 < args.length) {
                String spec = args[i + 1];
                int hash = spec.lastIndexOf('#');
                if (hash < 0) {
                    System.err.println("--pr expects owner/name#number, got " + spec);
                    System.exit(2);
                }
                int number = Integer.parseInt(spec.substring(hash + 1));
                pipeline.authorIntent(com.codereview.agent.intake.GitHubPullRequest
                        .fetch(spec.substring(0, hash), number));
            }
        }
        if (!pipeline.authorIntent.isEmpty()) {
            System.out.println("author description: " + pipeline.authorIntent.title());
        }
        ReviewReport report = pipeline.run(repository, args[1], args[2]);
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

    private static Comment unverified(PlannedUnit unit) {
        String ruleId = unit.ruleHits().isEmpty() ? "" : unit.ruleHits().get(0).rule().id();
        String meaning = RuleCatalog.explain(ruleId);
        List<String> clauses = policyIdsOf(unit);
        boolean removed = unit.changes().stream()
                .anyMatch(change -> change.kind() == StructuralChange.Kind.UNIT_REMOVED);
        String body;
        if (removed) {
            // "The contract changed" is technically true for a deletion, but it reads as if the
            // method were still there with a new signature. Say what actually happened.
            body = "这个方法被删除了：如果仓库里还有调用方，删除会让它们编译失败或行为改变；"
                    + "深挖没有取到能下结论的证据，请确认删除是有意的。";
        } else if ("POLICY-CONSTRAINT".equals(ruleId) && !clauses.isEmpty()) {
            body = "强制条款 " + String.join("、", clauses)
                    + " 要求这处改动逐条对照，但深挖没有取到能下结论的证据。";
        } else if (meaning != null) {
            body = "规则 `" + ruleId + "` 命中：" + meaning
                    + "；深挖没有取到能下结论的证据，请确认这处改动是有意的。";
        } else {
            body = "这里被判为必查（" + unit.tierReason() + "），但深挖没有取到能下结论的证据。";
        }
        return new Comment(unit.unit().file(), unit.unit().startLine(),
                unit.category().name(), Comment.Status.QUESTION, body, List.of());
    }

    private static Comment.Status statusOf(VerifyResult verdict) {
        // A conclusion the investigator could not turn into a change request is a question for the
        // author, not an assertion, no matter how confident the critic was that it holds.
        boolean open = verdict.disposition() == VerifyResult.Disposition.QUESTION
                || verdict.finding().asksForConfirmation();
        return open ? Comment.Status.QUESTION : Comment.Status.ASSERTION;
    }

    private static InvestigationTask taskOf(PlannedUnit unit, List<FindingQuestion> questions) {
        return new InvestigationTask(
                unit.unit().key(),
                unit.unit().file(),
                unit.unit().after() == null ? 1 : unit.unit().after().startLine(),
                unit.unit().textBefore(),
                unit.unit().textAfter(),
                unit.changes().stream().map(change -> change.describe()).toList(),
                questions.stream().map(question -> question.text()).toList(),
                unit.facts());
    }
}
