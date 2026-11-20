package com.codereview.agent.eval;

/**
 * What the pipeline did with one mutation. The counts are deliberately granular: a report that only
 * says "detected" cannot tell a triage miss from a verification retraction.
 *
 * @param anchorLine the line the injected defect ends up on, 0 for a clean change
 * @param lineMatched whether a surviving finding landed within {@link EvalReport#LINE_TOLERANCE}
 *                    lines of the anchor
 */
public record CaseOutcome(String id, boolean defective, String kind, int unitsScheduled,
        String tier, int findings, int kept, int downgraded, int dropped, int invalidRetractions,
        boolean lineMatched, int anchorLine) {
}
