package com.codereview.agent.triage.plan;

import com.codereview.agent.triage.model.IntentSummary;
import com.codereview.agent.triage.screen.ScreenedFile;
import com.codereview.agent.triage.tier.TierPolicy;

import java.util.List;

/**
 * The output of triage: not comments, a plan for the investigation stage.
 *
 * <p>Nothing here is a verdict about the code. Every unit carries the questions that would
 * settle it, and the units that were not investigated are still listed with the reason, so
 * the plan can be read as an honest account of what was and was not looked at.
 *
 * <p>Skipped files are listed for the same reason: "nothing changed there" and "we chose not to
 * look" are different statements, and only one of them is true at a time.
 */
public record TriagePlan(
        String baseRevision,
        String headRevision,
        IntentSummary intent,
        List<PlannedUnit> units,
        TriageStats stats,
        List<ScreenedFile> screenedOut) {

    public TriagePlan {
        units = List.copyOf(units);
        screenedOut = List.copyOf(screenedOut);
    }

    public TriagePlan(String baseRevision, String headRevision, IntentSummary intent,
            List<PlannedUnit> units, TriageStats stats) {
        this(baseRevision, headRevision, intent, units, stats, List.of());
    }

    public List<PlannedUnit> scheduled() {
        return units.stream().filter(PlannedUnit::scheduled).toList();
    }

    public long cheapGateSkipped() {
        return units.stream()
                .filter(unit -> TierPolicy.isCheapGate(unit.tierReason()))
                .count();
    }

    public String render() {
        StringBuilder text = new StringBuilder();
        text.append("改动意图：").append(intent.intent().isBlank() ? "（尚未生成）" : intent.intent())
                .append('\n');
        for (String concern : intent.crossFileConcerns()) {
            text.append("跨文件疑点：").append(concern).append('\n');
        }
        text.append('\n');
        for (PlannedUnit unit : units) {
            text.append(unit.describe()).append('\n');
        }
        if (!screenedOut.isEmpty()) {
            text.append('\n').append("被筛除的文件：").append('\n');
            for (ScreenedFile file : screenedOut) {
                text.append("  ").append(file.path()).append(" — ").append(file.reason()).append('\n');
            }
        }
        text.append('\n').append(stats.summary()).append('\n');
        return text.toString();
    }
}
