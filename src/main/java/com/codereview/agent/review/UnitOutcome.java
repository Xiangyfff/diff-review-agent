package com.codereview.agent.review;

/**
 * What happened to one scheduled unit, kept next to the comments so a run can be explained.
 *
 * <p>The outcome is one of the six the investigation loop can end in; a run that reports only
 * comments hides how many units were dropped for budget or failed to produce a usable conclusion.
 */
public record UnitOutcome(String file, String unitKey, String tier, String riskCategory,
        String outcome, int toolCalls, int findings, int verifyCalls, java.util.List<String> abandoned,
        boolean sampled, java.util.List<String> policies) {

    public UnitOutcome(String file, String unitKey, String tier, String riskCategory, String outcome,
            int toolCalls, int findings, int verifyCalls, java.util.List<String> abandoned) {
        this(file, unitKey, tier, riskCategory, outcome, toolCalls, findings, verifyCalls, abandoned,
                false, java.util.List.of());
    }

    public UnitOutcome(String file, String unitKey, String tier, String riskCategory, String outcome,
            int toolCalls, int findings, int verifyCalls, java.util.List<String> abandoned,
            boolean sampled) {
        this(file, unitKey, tier, riskCategory, outcome, toolCalls, findings, verifyCalls, abandoned,
                sampled, java.util.List.of());
    }

    public UnitOutcome {
        abandoned = abandoned == null ? java.util.List.of() : java.util.List.copyOf(abandoned);
        policies = policies == null ? java.util.List.of() : java.util.List.copyOf(policies);
    }
}
