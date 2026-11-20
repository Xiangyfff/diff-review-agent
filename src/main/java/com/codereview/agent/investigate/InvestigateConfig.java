package com.codereview.agent.investigate;

/**
 * The hard limits of one investigation.
 *
 * <p>Tool calls are the budget; turns are a safety valve for a model that keeps talking without
 * calling anything. Both are counted before the run so the result can say "unfinished" and mean it.
 *
 * @param maxToolCalls  tool calls one unit may make
 * @param maxTurns      model turns one unit may take
 * @param maxFindings   conclusions one unit may produce, so one unit cannot bury the others
 */
public record InvestigateConfig(int maxToolCalls, int maxTurns, int maxFindings) {

    public InvestigateConfig {
        if (maxToolCalls < 1) {
            throw new IllegalArgumentException("at least one tool call must be allowed");
        }
        if (maxTurns < 1) {
            throw new IllegalArgumentException("at least one turn must be allowed");
        }
        if (maxFindings < 1) {
            throw new IllegalArgumentException("at least one finding must be allowed");
        }
    }

    public static InvestigateConfig defaults() {
        return new InvestigateConfig(8, 12, 2);
    }
}
