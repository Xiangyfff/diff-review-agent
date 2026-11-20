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
import com.codereview.agent.triage.plan.PlanAssembler;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.plan.TriagePlan;
import com.codereview.agent.triage.plan.TriageStats;
import com.codereview.agent.triage.rules.RuleCatalog;
import com.codereview.agent.triage.rules.RuleContext;
import com.codereview.agent.triage.rules.RuleHit;
import com.codereview.agent.triage.screen.Screening;
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

    public TriageService(TriageModel model, TriageConfig config) {
        this(model, config, new Screening(), new SourceParser(), new RuleCatalog(RuleCatalog.defaults().rules()));
    }

    public TriageService(TriageModel model, TriageConfig config, Screening screening,
                         SourceParser parser, RuleCatalog rules) {
        this(model, config, screening, parser, rules, SymbolIndex.empty());
    }

    public TriageService(TriageModel model, TriageConfig config, Screening screening,
                         SourceParser parser, RuleCatalog rules, SymbolIndex index) {
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
    }

    public static TriageService offline() {
        return new TriageService(new MockTriageModel(), TriageConfig.defaults());
    }

    public TriagePlan triage(Path repositoryPath, String baseRevision, String headRevision) throws IOException {
        List<FileChange> changes = new RepositoryChangeSource().changes(repositoryPath, baseRevision, headRevision);
        SymbolIndex repositoryIndex = new IndexBuilder(parser).build(repositoryPath).index();
        return plan(changes, baseRevision, headRevision, repositoryIndex);
    }

    public TriagePlan triage(List<FileChange> changes, String baseRevision, String headRevision) {
        return plan(changes, baseRevision, headRevision, index);
    }

    private TriagePlan plan(List<FileChange> changes, String baseRevision, String headRevision,
                            SymbolIndex symbolIndex) {
        int screenedOut = 0;
        List<FileChange> reviewed = new ArrayList<>();
        List<AnalysisUnit> units = new ArrayList<>();

        for (FileChange change : changes) {
            if (!screening.screen(change).review()) {
                screenedOut++;
                continue;
            }
            reviewed.add(change);
            CompilationUnit before = parseOrNull(change.oldText());
            CompilationUnit after = parseOrNull(change.newText());
            units.addAll(mapper.map(change, before, after));
        }

        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger unparsed = new AtomicInteger();
        List<PlanAssembler.Candidate> candidates = assessAll(units, modelCalls, unparsed, symbolIndex);

        PlanAssembler.Result assembled = assembler.assemble(candidates, config.maxInvestigateUnits());

        IntentSummary intent = IntentSummary.empty();
        if (!units.isEmpty()) {
            modelCalls.incrementAndGet();
            intent = model.summarize(overview(reviewed, assembled.units()));
        }

        TriageStats stats = stats(changes.size(), screenedOut, assembled.units(),
                assembled.invalidQuestions(), assembled.calibrationDisagreements(),
                unparsed.get(), modelCalls.get());
        return new TriagePlan(baseRevision, headRevision, intent, assembled.units(), stats);
    }

    private List<PlanAssembler.Candidate> assessAll(List<AnalysisUnit> units, AtomicInteger modelCalls,
                                                    AtomicInteger unparsed, SymbolIndex symbolIndex) {
        if (units.isEmpty()) {
            return List.of();
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PlanAssembler.Candidate>> futures = new ArrayList<>(units.size());
            for (AnalysisUnit unit : units) {
                futures.add(executor.submit(() -> assessOne(unit, modelCalls, unparsed, symbolIndex)));
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
                                              SymbolIndex symbolIndex) {
        StructuralDeltaDetector.Detection detection = detector.detect(unit);
        if (!detection.parsed()) {
            unparsed.incrementAndGet();
        }
        RuleContext context = new RuleContext(unit, detection.changes());
        List<RuleHit> hits = rules.evaluate(context);
        TierPolicy.Decision decision = tierPolicy.decide(context, hits);

        String facts = factsFor(unit, symbolIndex);
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
                    assessment.worthInvestigating(), assessment.abstainReason(), assessment.rationale());
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
    private static String factsFor(AnalysisUnit unit, SymbolIndex index) {
        if (index.isEmpty()) {
            return "";
        }
        SymbolFacts facts = index.factsFor(simpleName(unit.name()));
        return facts.isEmpty() ? "" : facts.describe();
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

    private static ChangeOverview overview(List<FileChange> files, List<PlannedUnit> units) {
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
        return new ChangeOverview(summaries, added, removed, units.size());
    }

    private static TriageStats stats(int filesSeen, int screenedOut, List<PlannedUnit> units,
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
        return new TriageStats(filesSeen, screenedOut, units.size(), must, judge, skip, scheduled,
                budgetSkipped, invalidQuestions, disagreements, unparsed, modelCalls);
    }
}
