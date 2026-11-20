package com.codereview.agent.triage.plan;

import com.codereview.agent.triage.model.FindingQuestion;
import com.codereview.agent.triage.model.UnitAssessment;
import com.codereview.agent.triage.rules.RiskCategory;
import com.codereview.agent.triage.rules.RuleHit;
import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.tier.Tier;
import com.codereview.agent.triage.unit.AnalysisUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    public record Result(List<PlannedUnit> units, int invalidQuestions, int calibrationDisagreements) {

        public Result {
            units = List.copyOf(units);
        }
    }

    public Result assemble(List<Candidate> candidates, int maxInvestigateUnits) {
        List<PlannedUnit> planned = new ArrayList<>();
        int invalidQuestions = 0;
        int disagreements = 0;

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

            planned.add(new PlannedUnit(candidate.unit(), candidate.tier(), candidate.category(),
                    candidate.tierReason(), candidate.changes(), candidate.ruleHits(), valid, synthetic,
                    assessment.abstainReason(), false, candidate.parseFailed(), candidate.facts()));
        }

        return new Result(applyBudget(planned, maxInvestigateUnits), invalidQuestions, disagreements);
    }

    /**
     * The rule layer already knows what is suspicious, so a unit the rules promoted is never
     * silently dropped just because the model failed to phrase a question.
     */
    private static FindingQuestion fallbackQuestion(Candidate candidate) {
        for (RuleHit hit : candidate.ruleHits()) {
            if (hit.evidence().isEmpty()) {
                continue;
            }
            StructuralChange change = hit.evidence().get(0);
            String target = FindingQuestion.symbolIn(change.detail(), candidate.unit().name());
            return new FindingQuestion(target, hit.rule().rationale() + "（规则 " + hit.rule().id() + " 触发的兜底问题）");
        }
        return null;
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
            // Mirrors PlannedUnit.scheduled(): a mandatory unit is investigated whether or not
            // the model phrased a question, so it must consume budget too.
            boolean wantsInvestigation = unit.tier() == Tier.MUST_INVESTIGATE
                    || (unit.tier() == Tier.JUDGE && !unit.questions().isEmpty());
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
