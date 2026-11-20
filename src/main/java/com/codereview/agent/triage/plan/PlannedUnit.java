package com.codereview.agent.triage.plan;

import com.codereview.agent.triage.model.FindingQuestion;
import com.codereview.agent.triage.rules.RiskCategory;
import com.codereview.agent.triage.rules.RuleHit;
import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.tier.Tier;
import com.codereview.agent.triage.unit.AnalysisUnit;

import java.util.List;

/**
 * One unit as the triage stage hands it on: a tier, a category, the changes that were
 * detected, and the questions worth answering.
 *
 * <p>This is the boundary between the cheap stage and the expensive one. Everything the
 * investigation stage is allowed to know is here; nothing about how the decision was
 * reached travels any further, which is what keeps the second stage's context small.
 */
public record PlannedUnit(
        AnalysisUnit unit,
        Tier tier,
        RiskCategory category,
        String tierReason,
        List<StructuralChange> changes,
        List<RuleHit> ruleHits,
        List<FindingQuestion> questions,
        boolean syntheticQuestions,
        String abstainReason,
        boolean budgetSkipped,
        boolean parseFailed,
        String facts) {

    public PlannedUnit {
        changes = List.copyOf(changes);
        ruleHits = List.copyOf(ruleHits);
        questions = List.copyOf(questions);
        abstainReason = abstainReason == null ? "" : abstainReason;
        facts = facts == null ? "" : facts;
    }

    /** Whether the expensive stage will actually run on this unit. */
    public boolean scheduled() {
        if (budgetSkipped) {
            return false;
        }
        return tier == Tier.MUST_INVESTIGATE
                || (tier == Tier.JUDGE && !questions.isEmpty());
    }

    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append('[').append(tier).append("] ").append(unit.file())
                .append(':').append(unit.startLine()).append(' ').append(unit.key())
                .append(" (").append(category).append(')');
        if (parseFailed) {
            text.append(" !parse-failed");
        }
        if (budgetSkipped) {
            text.append(" !budget");
        }
        for (FindingQuestion question : questions) {
            text.append("\n    ? ").append(question.describe());
        }
        if (questions.isEmpty() && !abstainReason.isBlank()) {
            text.append("\n    (skipped: ").append(abstainReason).append(')');
        }
        return text.toString();
    }
}
