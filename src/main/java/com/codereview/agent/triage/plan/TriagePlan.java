package com.codereview.agent.triage.plan;

import com.codereview.agent.triage.model.IntentSummary;
import com.codereview.agent.triage.tier.TierPolicy;

import java.util.List;

/**
 * The output of triage: not comments, a plan for the investigation stage.
 *
 * <p>Nothing here is a verdict about the code. Every unit carries the questions that would
 * settle it, and the units that were not investigated are still listed with the reason, so
 * the plan can be read as an honest account of what was and was not looked at.
 */
public record TriagePlan(
        String baseRevision,
        String headRevision,
        IntentSummary intent,
        List<PlannedUnit> units,
        TriageStats stats) {

    public TriagePlan {
        units = List.copyOf(units);
    }

    public List<PlannedUnit> scheduled() {
        return units.stream().filter(PlannedUnit::scheduled).toList();
    }

    /**
     * How many units the cheap gate waved through without a model call. Derived from the recorded
     * reason instead of counted separately so the number cannot drift away from the units it
     * describes.
     */
    public long cheapGateSkipped() {
        return units.stream()
                .filter(unit -> TierPolicy.isCheapGate(unit.tierReason()))
                .count();
    }

    public String render() {
        StringBuilder text = new StringBuilder();
        text.append("改动意图：").append(intent.intent().isBlank() ? "（未生成）" : intent.intent()).append('\n');
        for (String concern : intent.crossFileConcerns()) {
            text.append("跨文件疑点：").append(concern).append('\n');
        }
        text.append('\n');
        for (PlannedUnit unit : units) {
            text.append(unit.describe()).append('\n');
        }
        text.append('\n').append(stats.summary()).append('\n');
        return text.toString();
    }
}
