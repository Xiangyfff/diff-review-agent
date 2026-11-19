package com.codereview.agent.triage.model;

import java.util.List;

/**
 * What the model said about one unit.
 *
 * <p>{@code worthInvestigating} is ignored for units the rules already promoted; it is
 * still recorded, because "the rule said check this and the model said skip it" is the
 * measurement that says how much the model's judgement can be trusted.
 */
public record UnitAssessment(
        List<FindingQuestion> questions,
        boolean worthInvestigating,
        String abstainReason,
        String rationale) {

    public UnitAssessment {
        questions = List.copyOf(questions);
        abstainReason = abstainReason == null ? "" : abstainReason;
        rationale = rationale == null ? "" : rationale;
    }

    public static UnitAssessment declining(String reason) {
        return new UnitAssessment(List.of(), false, reason, "");
    }
}
