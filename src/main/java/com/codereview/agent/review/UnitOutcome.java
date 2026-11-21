package com.codereview.agent.review;

/**
 * What happened to one scheduled unit, kept next to the comments so a run can be explained.
 *
 * <p>The outcome is one of the six the investigation loop can end in; a run that reports only
 * comments hides how many units were dropped for budget or failed to produce a usable conclusion.
 */
public record UnitOutcome(String file, String unitKey, String tier, String riskCategory,
        String outcome, int toolCalls, int findings, int verifyCalls, java.util.List<String> abandoned) {

    public UnitOutcome {
        abandoned = abandoned == null ? java.util.List.of() : java.util.List.copyOf(abandoned);
    }
}
