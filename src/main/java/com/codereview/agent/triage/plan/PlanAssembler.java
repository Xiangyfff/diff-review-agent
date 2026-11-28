package com.codereview.agent.triage.plan;

import com.codereview.agent.triage.model.FindingQuestion;
import com.codereview.agent.triage.model.UnitAssessment;
import com.codereview.agent.triage.model.Witness;
import com.codereview.agent.triage.rules.RiskCategory;
import com.codereview.agent.triage.rules.RuleHit;
import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.tier.Tier;
import com.codereview.agent.triage.unit.AnalysisUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns per-unit assessments into the plan: drops questions that name nothing, fills in a
 * question where a rule demanded an investigation but the model produced none, orders the
 * units and applies the budget.
 *
 * <p>The anchoring check is the one mechanical gate in this stage. A question whose target
 * does not appear anywhere in the unit it is about was invented, and handing it to the
 * investigation stage would spend real tool calls chasing a symbol that does not exist.
 */
public final class PlanAssembler {

    public record Candidate(
            AnalysisUnit unit,
            Tier tier,
            RiskCategory category,
            String tierReason,
            List<StructuralChange> changes,
            List<RuleHit> ruleHits,
            UnitAssessment assessment,
            boolean parseFailed,
            String facts) {
    }

    /** The reason prefix of a unit the model asked to skip and the checker let it. */
    public static final String MODEL_DECLINED_REASON = "model declined: ";

    private static final int MODEL_SKIP_PERCENT = 30;

    /** A witness is only useful if it names a log call; a business literal is not a log line. */
    private static final Pattern LOG_CALL = Pattern.compile(
            "(?i)(\\blog(?:ger)?\\.(?:info|warn|error|debug|trace)|System\\.(?:out|err)\\.print)");

    public record Result(List<PlannedUnit> units, int invalidQuestions, int calibrationDisagreements,
            /** Units the model asked to skip and the witness check accepted. */
            int modelSkips) {

        public Result {
            units = List.copyOf(units);
        }

        public Result(List<PlannedUnit> units, int invalidQuestions, int calibrationDisagreements) {
            this(units, invalidQuestions, calibrationDisagreements, 0);
        }
    }

    public Result assemble(List<Candidate> candidates, int maxInvestigateUnits) {
        List<PlannedUnit> planned = new ArrayList<>();
        int invalidQuestions = 0;
        int disagreements = 0;
        int modelSkips = 0;
        int judgeUnits = (int) candidates.stream().filter(c -> c.tier() == Tier.JUDGE).count();
        // A model that declines everything must not empty the review. The cap is a floor, so three
        // judge units can never all be lost to an impression.
        int skipAllowance = judgeUnits * MODEL_SKIP_PERCENT / 100;

        for (Candidate candidate : candidates) {
            UnitAssessment assessment = candidate.assessment();
            if (candidate.tier() == Tier.MUST_INVESTIGATE && !assessment.worthInvestigating()) {
                // The rule said this must be looked at and the model said it need not be.
                // The rule wins, and the disagreement is recorded as a calibration signal.
                disagreements++;
            }

            List<FindingQuestion> valid = new ArrayList<>();
            for (FindingQuestion question : assessment.questions()) {
                if (question.isWellFormed() && anchored(question, candidate.unit())) {
                    valid.add(question);
                } else {
                    invalidQuestions++;
                }
            }

            boolean synthetic = false;
            if (valid.isEmpty() && candidate.tier() == Tier.MUST_INVESTIGATE) {
                FindingQuestion fallback = fallbackQuestion(candidate);
                if (fallback != null) {
                    valid.add(fallback);
                    synthetic = true;
                }
            }

            boolean decline = candidate.tier() == Tier.JUDGE && !assessment.worthInvestigating()
                    && modelSkips < skipAllowance && witnessProblem(candidate).isEmpty();
            if (decline) {
                modelSkips++;
                planned.add(new PlannedUnit(candidate.unit(), Tier.SKIP, candidate.category(),
                        MODEL_DECLINED_REASON + assessment.abstainReason(),
                        candidate.changes(), candidate.ruleHits(), List.of(), false,
                        assessment.abstainReason(), false, candidate.parseFailed(), candidate.facts()));
                continue;
            }
            planned.add(new PlannedUnit(candidate.unit(), candidate.tier(), candidate.category(),
                    candidate.tierReason(), candidate.changes(), candidate.ruleHits(), valid, synthetic,
                    assessment.abstainReason(), false, candidate.parseFailed(), candidate.facts()));
        }

        return new Result(applyBudget(planned, maxInvestigateUnits), invalidQuestions, disagreements,
                modelSkips);
    }

    /**
     * The rule layer already knows what is suspicious, so a unit the rules promoted is never
     * silently dropped just because the model failed to phrase a question.
     */
    private static FindingQuestion fallbackQuestion(Candidate candidate) {
        for (RuleHit hit : candidate.ruleHits()) {
            // A rule can gate on a fact rather than on a shape ("this file handles auth", "code was
            // deleted"). Those hits carry no evidence, and skipping them used to leave a unit
            // scheduled with nothing to investigate: the loop opened, found an empty queue and
            // reported a conclusion-free unit. The rule's own rationale is a usable question.
            String target = candidate.unit().name();
            if (!hit.evidence().isEmpty()) {
                target = FindingQuestion.symbolIn(hit.evidence().get(0).detail(), target);
            }
            return new FindingQuestion(target,
                    hit.rule().rationale() + "（规则 " + hit.rule().id() + " 触发的兜底问题）");
        }
        return null;
    }

    /**
     * Checks the mechanical part of a decline. Only two claims are checkable without a model: the
     * file is a test, or the change is a literal inside a log call. Anything else — "this looks
     * intentional", "the caller already checks it" — cannot be verified here and therefore cannot
     * remove a unit from review.
     */
    private static Optional<String> witnessProblem(Candidate candidate) {
        Witness witness = candidate.assessment().witness();
        if (!witness.present()) {
            return Optional.of("no witness");
        }
        return switch (witness.kind()) {
            case Witness.TEST_ONLY -> isTestPath(candidate.unit().file())
                    ? Optional.empty()
                    : Optional.of("the unit is not test code");
            case Witness.LOG_ONLY -> {
                boolean literalOnly = !candidate.changes().isEmpty()
                        && candidate.changes().stream()
                        .allMatch(change -> change.kind() == StructuralChange.Kind.LITERAL_CHANGED);
                boolean quoted = witness.quote().length() >= 8
                        && (candidate.unit().textAfter().contains(witness.quote())
                        || candidate.unit().textBefore().contains(witness.quote()));
                boolean logCall = LOG_CALL.matcher(witness.quote()).find();
                yield literalOnly && quoted && logCall
                        ? Optional.empty()
                        : Optional.of("the change is not only a literal in a log call");
            }
            default -> Optional.of("unknown witness kind: " + witness.kind());
        };
    }

    private static boolean isTestPath(String path) {
        String lower = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return lower.contains("/src/test/") || lower.startsWith("src/test/")
                || lower.endsWith("test.java") || lower.endsWith("tests.java")
                || lower.endsWith("it.java");
    }

    /** A target counts as anchored when the unit's source actually contains it. */
    private static boolean anchored(FindingQuestion question, AnalysisUnit unit) {
        String target = question.target().trim();
        if (target.equals(unit.name())) {
            return true;
        }
        return unit.textBefore().contains(target) || unit.textAfter().contains(target);
    }

    private static List<PlannedUnit> applyBudget(List<PlannedUnit> planned, int maxInvestigateUnits) {
        List<PlannedUnit> ordered = new ArrayList<>(planned);
        ordered.sort(ORDER);

        int scheduled = 0;
        List<PlannedUnit> result = new ArrayList<>(ordered.size());
        for (PlannedUnit unit : ordered) {
            // Mirrors PlannedUnit.scheduled(): every unit the cheap stage did not skip is
            // investigated. The judge tier no longer needs a model-phrased question to earn its
            // place, because the investigation stage now opens by raising its own questions.
            boolean wantsInvestigation = unit.tier() == Tier.MUST_INVESTIGATE
                    || unit.tier() == Tier.JUDGE;
            if (!wantsInvestigation) {
                result.add(unit);
                continue;
            }
            if (scheduled < maxInvestigateUnits) {
                scheduled++;
                result.add(unit);
            } else {
                result.add(new PlannedUnit(unit.unit(), unit.tier(), unit.category(), unit.tierReason(),
                        unit.changes(), unit.ruleHits(), unit.questions(), unit.syntheticQuestions(),
                        unit.abstainReason(), true, unit.parseFailed(), unit.facts()));
            }
        }
        return result;
    }

    private static final Comparator<PlannedUnit> ORDER = Comparator
            .comparing((PlannedUnit unit) -> unit.tier() == Tier.MUST_INVESTIGATE ? 0 : 1)
            .thenComparing(unit -> unit.tier() == Tier.SKIP ? 2 : (unit.budgetSkipped() ? 1 : 0))
            .thenComparing(PlannedUnit::category)
            .thenComparing(unit -> -unit.questions().size())
            .thenComparing(unit -> unit.unit().file())
            .thenComparing(unit -> unit.unit().key());
}
