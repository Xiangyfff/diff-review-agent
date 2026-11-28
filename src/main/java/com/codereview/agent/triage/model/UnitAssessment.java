package com.codereview.agent.triage.model;

import java.util.List;

/**
 * What the model said about one unit.
 *
 * <p>{@code worthInvestigating} is ignored for units the rules already promoted; it is
 * still recorded, because "the rule said check this and the model said skip it" is the
 * measurement that says how much the model's judgement can be trusted.
 *
 * <p>A model that wants a unit skipped now has to say what it saw ({@link Witness}), and the
 * assembler checks that claim before it is allowed to remove the unit from the plan.
 */
public record UnitAssessment(
        List<FindingQuestion> questions,
        boolean worthInvestigating,
        String abstainReason,
        String rationale,
        Witness witness) {

    public UnitAssessment {
        questions = List.copyOf(questions);
        abstainReason = abstainReason == null ? "" : abstainReason;
        rationale = rationale == null ? "" : rationale;
        witness = witness == null ? Witness.NONE : witness;
    }

    /** Kept for callers that predate witnesses; such a decline can never skip a unit. */
    public UnitAssessment(List<FindingQuestion> questions, boolean worthInvestigating,
            String abstainReason, String rationale) {
        this(questions, worthInvestigating, abstainReason, rationale, Witness.NONE);
    }

    public static UnitAssessment declining(String reason) {
        return new UnitAssessment(List.of(), false, reason, "");
    }
}
