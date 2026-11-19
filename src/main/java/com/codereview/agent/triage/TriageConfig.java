package com.codereview.agent.triage;

/**
 * The knobs of the triage stage.
 *
 * <p>The budget counts units, not tokens. A token budget is the more precise instrument and
 * the worse one to enforce: it is only known after the money is spent, and it starves a
 * single difficult unit in the middle of working on it. A unit count is decided up front,
 * is the same on every run, and produces a plan that can be explained.
 */
public record TriageConfig(
        int maxInvestigateUnits,
        int maxQuestionsPerUnit,
        int parallelism) {

    public static TriageConfig defaults() {
        return new TriageConfig(20, 4, 8);
    }

    public TriageConfig {
        if (maxInvestigateUnits < 1) {
            throw new IllegalArgumentException("at least one unit may be investigated");
        }
        if (maxQuestionsPerUnit < 1) {
            throw new IllegalArgumentException("at least one question per unit");
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
    }
}
