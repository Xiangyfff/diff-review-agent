package com.codereview.agent.investigate;

import java.util.List;

/**
 * What one unit's investigation produced, including the reasons it may have produced little.
 *
 * <p>The counts exist so a plan can report honestly: "twelve units, nine concluded, two ran out of
 * tool budget, one was abandoned with a stated reason" is a usable sentence, while "the agent
 * reviewed the diff" is not.
 */
public record InvestigationResult(
        String unitKey,
        String file,
        int line,
        List<Finding> findings,
        List<Abandoned> abandoned,
        int rejectedFindings,
        int toolCalls,
        int turns,
        List<Evidence> evidence,
        Outcome outcome) {

    public InvestigationResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        abandoned = abandoned == null ? List.of() : List.copyOf(abandoned);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public boolean concluded() {
        return !findings.isEmpty();
    }

    public String summary() {
        return unitKey + " — " + outcome + ", " + findings.size() + " finding(s), "
                + abandoned.size() + " abandoned, " + rejectedFindings + " rejected, "
                + toolCalls + " tool call(s), " + turns + " turn(s)";
    }

    public record Abandoned(String questionId, String question, String reason) {
    }

    /**
     * Why the loop stopped. Only CONCLUDED means every question it started with was answered.
     */
    public enum Outcome {
        CONCLUDED,
        TOOL_BUDGET_EXHAUSTED,
        TURN_BUDGET_EXHAUSTED,
        MAX_FINDINGS_REACHED,
        STALLED,
        MODEL_FAILED
    }
}
