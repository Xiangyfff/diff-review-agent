package com.codereview.agent.triage.plan;

/**
 * Counters for one triage run.
 *
 * <p>These are not decoration. Two of them are the numbers that decide whether the design
 * works: the investigate ratio says whether the cheap gate is actually cheap, and the
 * calibration disagreements say whether the rules and the model agree about what matters.
 * The rest exist so that a run can explain itself: how much was screened out, how many
 * questions the model produced that had to be thrown away, how many units could not be
 * parsed.
 */
public record TriageStats(
        int filesSeen,
        int filesScreenedOut,
        int unitsTotal,
        int unitsMust,
        int unitsJudge,
        int unitsSkip,
        int unitsScheduled,
        int unitsBudgetSkipped,
        int invalidQuestions,
        int calibrationDisagreements,
        int unparsedUnits,
        int modelCalls) {

    public double investigateRatio() {
        return unitsTotal == 0 ? 0.0 : (double) unitsScheduled / unitsTotal;
    }

    public String summary() {
        return "文件 " + filesSeen + "（筛除 " + filesScreenedOut + "），单元 " + unitsTotal
                + "（必查 " + unitsMust + "、待判断 " + unitsJudge + "、跳过 " + unitsSkip + "），"
                + "实际深挖 " + unitsScheduled + "（占 "
                + String.format(java.util.Locale.ROOT, "%.0f%%", investigateRatio() * 100)
                + "，预算截断 " + unitsBudgetSkipped + "），"
                + "模型调用 " + modelCalls + "，丢弃的问题 " + invalidQuestions
                + "，模型与规则分歧 " + calibrationDisagreements
                + "，无法解析的单元 " + unparsedUnits;
    }
}
