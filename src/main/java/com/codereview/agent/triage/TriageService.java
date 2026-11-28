package com.codereview.agent.triage;

import com.codereview.agent.triage.diff.FileChange;
import com.codereview.agent.triage.diff.RepositoryChangeSource;
import com.codereview.agent.triage.index.IndexBuilder;
import com.codereview.agent.triage.index.SymbolFacts;
import com.codereview.agent.triage.index.SymbolIndex;
import com.codereview.agent.triage.model.ChangeOverview;
import com.codereview.agent.triage.model.IntentSummary;
import com.codereview.agent.triage.model.MockTriageModel;
import com.codereview.agent.triage.model.TriageModel;
import com.codereview.agent.triage.model.UnitAssessment;
import com.codereview.agent.triage.model.UnitPrompt;
import com.codereview.agent.triage.parse.SourceParser;
import com.codereview.agent.triage.policy.OllamaEmbedder;
import com.codereview.agent.triage.policy.PolicyExemption;
import com.codereview.agent.triage.policy.PolicyHit;
import com.codereview.agent.triage.policy.PolicyIndex;
import com.codereview.agent.triage.policy.PolicyLibrary;
import com.codereview.agent.triage.policy.PolicyQuery;
import com.codereview.agent.triage.plan.PlanAssembler;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.plan.TriagePlan;
import com.codereview.agent.triage.plan.TriageStats;
import com.codereview.agent.triage.rules.RuleCatalog;
import com.codereview.agent.triage.rules.RuleContext;
import com.codereview.agent.triage.rules.RuleHit;
import com.codereview.agent.triage.screen.ScreenedFile;
import com.codereview.agent.triage.screen.Screening;
import com.codereview.agent.triage.staticanalysis.SourceStaticAnalysis;
import com.codereview.agent.triage.staticanalysis.StaticAnalysis;
import com.codereview.agent.triage.staticanalysis.StaticWarning;
import com.codereview.agent.triage.structure.StructuralDeltaDetector;
import com.codereview.agent.triage.tier.Tier;
import com.codereview.agent.triage.tier.TierPolicy;
import com.codereview.agent.triage.unit.AnalysisUnit;
import com.codereview.agent.triage.unit.UnitMapper;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The triage stage: from a change to a plan, without a single tool call and without a
 * verdict about the code.
 *
 * <p>Order of work is fixed. Screen the files, map hunks to declarations, detect structural
 * changes, let the rules decide the tier, and only then ask the model. The model is the
 * expensive step, so everything that can remove work happens before it.
 *
 * <p>Each unit is assessed in its own virtual thread. The model call is pure I/O and the
 * mock is stateless, so the units are independent by construction; results are re-ordered
 * by index afterwards, which keeps the plan reproducible.
 */
public final class TriageService {

    private final Screening screening;
    private final SourceParser parser;
    private final UnitMapper mapper;
    private final StructuralDeltaDetector detector;
    private final RuleCatalog rules;
    private final TierPolicy tierPolicy;
    private final PlanAssembler assembler;
    private final TriageModel model;
    private final TriageConfig config;
    private final SymbolIndex index;
    private final StaticAnalysis staticAnalysis = new SourceStaticAnalysis();
    private final PolicyIndex policies;

    /** Whether a repository without attached policies may still load them from docs/policies. */
    private final boolean policyRetrieval;

    /** What the author says the change is for, if anyone said it. */
    private final com.codereview.agent.triage.model.AuthorIntent authorIntent;

    /** At most this many policy excerpts reach one unit, and only above the score cut. */
    private static final int POLICY_TOP_K = 3;
    private static final double POLICY_MIN_SCORE = 0.55;

    public TriageService(TriageModel model, TriageConfig config) {
        this(model, config, new Screening(), new SourceParser(), new RuleCatalog(RuleCatalog.defaults().rules()));
    }

    public TriageService(TriageModel model, TriageConfig config, Screening screening,
                         SourceParser parser, RuleCatalog rules) {
        this(model, config, screening, parser, rules, SymbolIndex.empty());
    }

    public TriageService(TriageModel model, TriageConfig config, Screening screening,
                         SourceParser parser, RuleCatalog rules, SymbolIndex index) {
        this(model, config, screening, parser, rules, index, PolicyIndex.empty());
    }

    public TriageService(TriageModel model, TriageConfig config, Screening screening,
                         SourceParser parser, RuleCatalog rules, SymbolIndex index,
                         PolicyIndex policies) {
        this.screening = screening;
        this.parser = parser;
        this.mapper = new UnitMapper();
        this.detector = new StructuralDeltaDetector(parser);
        this.rules = rules;
        this.tierPolicy = new TierPolicy();
        this.assembler = new PlanAssembler();
        this.model = model;
        this.config = config;
        this.index = index;
        this.policies = policies == null ? PolicyIndex.empty() : policies;
        this.policyRetrieval = true;
        this.authorIntent = com.codereview.agent.triage.model.AuthorIntent.empty();
    }

    /** The same service with policy documents attached; used when the repository has them. */
    public TriageService withPolicies(PolicyIndex index) {
        return new TriageService(model, config, screening, parser, rules, this.index, index);
    }

    /**
     * The same service with policy retrieval turned off. This exists to measure what the policy
     * documents are worth: run the same corpus with and without, and compare the two reports. A
     * switch you cannot flip is not an experiment.
     */
    public TriageService withoutPolicies() {
        TriageService off = new TriageService(model, config, screening, parser, rules, index);
        return new TriageService(off, false);
    }

    private TriageService(TriageService other, boolean policyRetrieval) {
        this.model = other.model;
        this.config = other.config;
        this.screening = other.screening;
        this.parser = other.parser;
        this.mapper = other.mapper;
        this.detector = other.detector;
        this.rules = other.rules;
        this.tierPolicy = other.tierPolicy;
        this.assembler = other.assembler;
        this.index = other.index;
        this.policies = other.policies;
        this.policyRetrieval = policyRetrieval;
        this.authorIntent = other.authorIntent;
    }

    /** The same service carrying the author's own description of the change. */
    public TriageService withAuthorIntent(com.codereview.agent.triage.model.AuthorIntent intent) {
        TriageService carrying = new TriageService(this, policyRetrieval);
        return new TriageService(carrying, intent);
    }

    private TriageService(TriageService other, com.codereview.agent.triage.model.AuthorIntent intent) {
        this.model = other.model;
        this.config = other.config;
        this.screening = other.screening;
        this.parser = other.parser;
        this.mapper = other.mapper;
        this.detector = other.detector;
        this.rules = other.rules;
        this.tierPolicy = other.tierPolicy;
        this.assembler = other.assembler;
        this.index = other.index;
        this.policies = other.policies;
        this.policyRetrieval = other.policyRetrieval;
        this.authorIntent = intent == null ? com.codereview.agent.triage.model.AuthorIntent.empty() : intent;
    }

    /** Visible for tests: whether this instance may still load docs/policies on its own. */
    boolean policyRetrieval() {
        return policyRetrieval;
    }

    public static TriageService offline() {
        return new TriageService(new MockTriageModel(), TriageConfig.defaults());
    }

    /**
     * The same service, but files under the given path prefixes are never screened out. Used to pull
     * back something a reviewer believes screening got wrong, without editing code and restarting.
     */
    public TriageService including(List<String> includePaths) {
        TriageService widened =
                new TriageService(model, config, new Screening(includePaths), parser, rules, index);
        return new TriageService(widened, policyRetrieval);
    }

    public TriagePlan triage(Path repositoryPath, String baseRevision, String headRevision) throws IOException {
        List<FileChange> changes = new RepositoryChangeSource().changes(repositoryPath, baseRevision, headRevision);
        SymbolIndex repositoryIndex = new IndexBuilder(parser).build(repositoryPath).index();
        return plan(changes, baseRevision, headRevision, repositoryIndex,
                policyRetrieval && policies.isEmpty() ? loadPolicies(repositoryPath) : policies);
    }

    /**
     * Policy documents live with the code they govern, under {@code docs/policies}. A repository
     * without them, or an embedding endpoint that is down, produces an empty index: the review
     * simply has no policy signal, which is a normal state and never a failed run.
     *
     * <p>Triage scores on samples: a clause that ships an example is ranked by the changed code
     * against that example, and its prose is used only for the identifier overlap. Prose-only
     * clauses keep the prose route, but they are the fallback, not the rule.
     */
    private static PolicyIndex loadPolicies(Path repositoryPath) {
        Path directory = repositoryPath.resolve("docs").resolve("policies");
        if (!java.nio.file.Files.isDirectory(directory)) {
            return PolicyIndex.empty();
        }
        try {
            return PolicyIndex.build(PolicyLibrary.load(directory), OllamaEmbedder.fromEnvironment(),
                    PolicyIndex.Scoring.SAMPLES);
        } catch (RuntimeException unavailable) {
            return PolicyIndex.empty();
        }
    }

    public TriagePlan triage(List<FileChange> changes, String baseRevision, String headRevision) {
        return plan(changes, baseRevision, headRevision, index, policies);
    }

    private TriagePlan plan(List<FileChange> changes, String baseRevision, String headRevision,
                            SymbolIndex symbolIndex, PolicyIndex policyIndex) {
        List<ScreenedFile> skipped = new ArrayList<>();
        List<FileChange> reviewed = new ArrayList<>();
        List<AnalysisUnit> units = new ArrayList<>();

        java.util.Map<String, List<StaticWarning>> warningsByPath = new java.util.HashMap<>();
        for (FileChange change : changes) {
            Screening.Decision decision = screening.screen(change);
            if (!decision.review()) {
                skipped.add(new ScreenedFile(change.displayPath(), decision.reason()));
                continue;
            }
            reviewed.add(change);
            CompilationUnit before = parseOrNull(change.oldText());
            CompilationUnit after = parseOrNull(change.newText());
            units.addAll(mapper.map(change, before, after));
            List<StaticWarning> warnings = staticAnalysis.onAddedLines(change);
            if (!warnings.isEmpty()) {
                warningsByPath.put(change.displayPath(), warnings);
            }
        }

        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger unparsed = new AtomicInteger();
        List<PlanAssembler.Candidate> candidates = assessAll(units, modelCalls, unparsed, symbolIndex,
                warningsByPath, policyIndex);

        PlanAssembler.Result assembled = assembler.assemble(candidates, config.maxInvestigateUnits());

        IntentSummary intent = IntentSummary.empty();
        if (!units.isEmpty()) {
            modelCalls.incrementAndGet();
            intent = model.summarize(overview(reviewed, assembled.units(), authorIntent));
        }

        TriageStats stats = stats(changes.size(), List.copyOf(skipped), assembled.units(),
                assembled.invalidQuestions(), assembled.calibrationDisagreements(),
                unparsed.get(), modelCalls.get());
        return new TriagePlan(baseRevision, headRevision, intent, assembled.units(), stats,
                skipped);
    }

    private List<PlanAssembler.Candidate> assessAll(List<AnalysisUnit> units, AtomicInteger modelCalls,
                                                    AtomicInteger unparsed, SymbolIndex symbolIndex,
                                                    java.util.Map<String, List<StaticWarning>> warningsByPath,
                                                    PolicyIndex policyIndex) {
        if (units.isEmpty()) {
            return List.of();
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PlanAssembler.Candidate>> futures = new ArrayList<>(units.size());
            for (AnalysisUnit unit : units) {
                List<StaticWarning> warnings = warningsFor(unit, warningsByPath);
                futures.add(executor.submit(
                        () -> assessOne(unit, modelCalls, unparsed, symbolIndex, warnings, policyIndex)));
            }
            List<PlanAssembler.Candidate> candidates = new ArrayList<>(units.size());
            for (Future<PlanAssembler.Candidate> future : futures) {
                candidates.add(future.get());
            }
            return candidates;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("triage interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("triage failed", failure.getCause());
        }
    }

    private PlanAssembler.Candidate assessOne(AnalysisUnit unit, AtomicInteger modelCalls, AtomicInteger unparsed,
                                              SymbolIndex symbolIndex, List<StaticWarning> warnings,
                                              PolicyIndex policyIndex) {
        StructuralDeltaDetector.Detection detection = detector.detect(unit);
        if (!detection.parsed()) {
            unparsed.incrementAndGet();
        }
        List<PolicyHit> policyHits = policyIndex.search(
                PolicyQuery.prose(unit, detection.changes()), PolicyQuery.code(unit),
                POLICY_TOP_K, Math.min(POLICY_MIN_SCORE, policyIndex.scoreCut()));
        RuleContext context = new RuleContext(unit, detection.changes(), warnings, policyHits);
        List<RuleHit> hits = rules.evaluate(context);
        PolicyExemption exemption = PolicyExemption.find(unit.file(), policyHits,
                Math.min(POLICY_MIN_SCORE, policyIndex.scoreCut()));
        if (exemption != null) {
            // The document says this rule does not apply here. Only the rules it names are removed;
            // anything else that fired still speaks.
            List<RuleHit> remaining = hits.stream()
                    .filter(hit -> !exemption.exempts(hit.rule().id()))
                    .toList();
            hits = remaining;
        }
        TierPolicy.Decision decision = tierPolicy.decide(context, hits, exemption);

        String facts = factsFor(unit, symbolIndex, warnings, policyHits);
        UnitAssessment assessment;
        if (decision.tier() == Tier.SKIP) {
            assessment = UnitAssessment.declining(decision.reason());
        } else {
            modelCalls.incrementAndGet();
            assessment = assessWithModel(unit, decision, detection.changes(), facts);
        }
        return new PlanAssembler.Candidate(unit, decision.tier(), decision.category(), decision.reason(),
                detection.changes(), hits, assessment, !detection.parsed(), facts);
    }

    /**
     * A model that throws or answers nonsense must not take the run down with it, and must
     * not silently downgrade a unit the rules promoted: the fallback keeps the tier and lets
     * the assembler build a question from the rule evidence.
     */
    private UnitAssessment assessWithModel(AnalysisUnit unit, TierPolicy.Decision decision,
                                           List<com.codereview.agent.triage.structure.StructuralChange> changes,
                                           String facts) {
        try {
            UnitAssessment assessment = model.assess(
                    UnitPrompt.of(unit, decision.tier(), decision.category(), changes, facts));
            if (assessment.questions().size() <= config.maxQuestionsPerUnit()) {
                return assessment;
            }
            return new UnitAssessment(assessment.questions().subList(0, config.maxQuestionsPerUnit()),
                    assessment.worthInvestigating(), assessment.abstainReason(),
                    assessment.rationale(), assessment.witness());
        } catch (RuntimeException failure) {
            return new UnitAssessment(List.of(), false,
                    "模型调用失败：" + failure.getClass().getSimpleName(), "");
        }
    }

    private CompilationUnit parseOrNull(String text) {
        if (text == null) {
            return null;
        }
        Optional<CompilationUnit> parsed = parser.parse(text);
        return parsed.orElse(null);
    }

    /**
     * The index answers name-level questions only. When it cannot find the symbol the prompt
     * stays silent rather than guessing, so the model never receives a fact nobody computed.
     */
    private static String factsFor(AnalysisUnit unit, SymbolIndex index, List<StaticWarning> warnings,
                                   List<PolicyHit> policyHits) {
        String symbolFacts = "";
        if (!index.isEmpty()) {
            SymbolFacts facts = index.factsFor(simpleName(unit.name()));
            symbolFacts = facts.isEmpty() ? "" : facts.describe();
        }
        StringBuilder facts = new StringBuilder(symbolFacts);
        if (!warnings.isEmpty()) {
            String reported = warnings.stream().map(StaticWarning::describe)
                    .collect(java.util.stream.Collectors.joining("；"));
            facts.append(facts.isEmpty() ? "" : " ").append("静态检查在新增代码上报告：")
                    .append(reported).append("。");
        }
        if (!policyHits.isEmpty()) {
            facts.append(facts.isEmpty() ? "" : " ")
                    .append("政策文档相关段落（这是规范摘录，不是代码证据）：");
            for (PolicyHit hit : policyHits) {
                facts.append(hit.describe()).append("；");
            }
        }
        return facts.toString();
    }

    /**
     * A warning belongs to a unit when its line falls inside that unit's new-side range. Warnings
     * outside every unit (for example a changed import) stay attached to the file and are dropped
     * here, because there is no unit that could carry them.
     */
    private static List<StaticWarning> warningsFor(AnalysisUnit unit,
                                                   java.util.Map<String, List<StaticWarning>> byPath) {
        List<StaticWarning> warnings = byPath.get(unit.file());
        if (warnings == null || unit.after() == null) {
            return List.of();
        }
        return warnings.stream()
                .filter(warning -> warning.line() >= unit.after().startLine()
                        && warning.line() <= unit.after().endLine())
                .toList();
    }

    private static String simpleName(String unitName) {
        String head = unitName;
        int parameters = head.indexOf('(');
        if (parameters > 0) {
            head = head.substring(0, parameters);
        }
        int comma = head.indexOf(',');
        if (comma > 0) {
            head = head.substring(0, comma);
        }
        int dot = head.lastIndexOf('.');
        return dot >= 0 ? head.substring(dot + 1) : head;
    }

    private static ChangeOverview overview(List<FileChange> files, List<PlannedUnit> units,
                                          com.codereview.agent.triage.model.AuthorIntent authorIntent) {
        Map<String, List<PlannedUnit>> byPath = new LinkedHashMap<>();
        for (PlannedUnit unit : units) {
            byPath.computeIfAbsent(unit.unit().file(), path -> new ArrayList<>()).add(unit);
        }
        List<ChangeOverview.FileSummary> summaries = new ArrayList<>();
        int added = 0;
        int removed = 0;
        for (FileChange file : files) {
            List<PlannedUnit> fileUnits = byPath.getOrDefault(file.displayPath(), List.of());
            List<String> changedUnits = fileUnits.stream().map(unit -> unit.unit().name()).toList();
            List<String> removedDeclarations = fileUnits.stream()
                    .filter(unit -> unit.unit().isDeleted())
                    .map(unit -> unit.unit().name())
                    .toList();
            int fileAdded = (int) file.edits().stream().mapToLong(FileChange.EditRange::addedLines).sum();
            int fileRemoved = (int) file.edits().stream().mapToLong(FileChange.EditRange::removedLines).sum();
            added += fileAdded;
            removed += fileRemoved;
            summaries.add(new ChangeOverview.FileSummary(file.displayPath(), fileAdded, fileRemoved,
                    changedUnits, removedDeclarations));
        }
        return new ChangeOverview(summaries, added, removed, units.size(), authorIntent);
    }

    private static TriageStats stats(int filesSeen, List<ScreenedFile> skipped,
            List<PlannedUnit> units,
                                     int invalidQuestions, int disagreements, int unparsed, int modelCalls) {
        int must = 0;
        int judge = 0;
        int skip = 0;
        int scheduled = 0;
        int budgetSkipped = 0;
        for (PlannedUnit unit : units) {
            switch (unit.tier()) {
                case MUST_INVESTIGATE -> must++;
                case JUDGE -> judge++;
                case SKIP -> skip++;
            }
            if (unit.scheduled()) {
                scheduled++;
            }
            if (unit.budgetSkipped()) {
                budgetSkipped++;
            }
        }
        return new TriageStats(filesSeen, skipped.size(), units.size(), must, judge, skip, scheduled,
                budgetSkipped, invalidQuestions, disagreements, unparsed, modelCalls);
    }
}
