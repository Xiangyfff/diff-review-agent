package com.codereview.agent.eval;

/**
 * What the pipeline did with one mutation. The counts are deliberately granular: a report that only
 * says "detected" cannot tell a triage miss from a verification retraction.
 *
 * @param anchorLine the line the injected defect ends up on, 0 for a clean change
 * @param lineMatched whether a surviving finding landed within {@link EvalReport#LINE_TOLERANCE}
 *                    lines of the anchor
 * @param evidenceBacked whether that finding cited evidence. A question posted because a
 *                    must-investigate unit ended empty also lands on the right line; counting it
 *                    as a detection would flatter the number and is exactly the kind of credit a
 *                    reviewer should not get
 */
public record CaseOutcome(String id, boolean defective, String kind, int unitsScheduled,
        String tier, int findings, int kept, int downgraded, int dropped, int merged,
        int invalidRetractions, boolean lineMatched, int anchorLine, boolean evidenceBacked) {

    public CaseOutcome(String id, boolean defective, String kind, int unitsScheduled, String tier,
            int findings, int kept, int downgraded, int dropped, int merged, int invalidRetractions,
            boolean lineMatched, int anchorLine) {
        this(id, defective, kind, unitsScheduled, tier, findings, kept, downgraded, dropped, merged,
                invalidRetractions, lineMatched, anchorLine, lineMatched && findings > 0);
    }
}
