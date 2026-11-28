package com.codereview.agent.investigate;

import java.util.List;

/**
 * What the loop hands to the model at the start of each turn.
 *
 * <p>It contains the unit, the questions still open, and a compact log of evidence ids already
 * obtained — never the full text of everything read so far. Keeping the raw text in the store and
 * the ids in the prompt is what stops the context from growing without bound.
 */
public record InvestigationState(
        String unitKey,
        String file,
        int line,
        String before,
        String after,
        List<String> changes,
        List<Question> open,
        List<Evidence> evidence,
        int toolCallsUsed,
        int toolCallsLeft,
        /** Why the previous conclusion was rejected, or an empty string. */
        String lastRejection,
        /** One line per tool call so far, including the ones that returned nothing. */
        List<String> attempts,
        /** Name-level facts from triage, or an empty string when there are none. */
        String facts,
        /** True before the model has raised its first questions. */
        boolean opening,
        /** Questions already answered, so the model does not re-litigate them. */
        List<InvestigationResult.QuestionState> resolved) {

    public InvestigationState {
        changes = List.copyOf(changes);
        open = List.copyOf(open);
        evidence = List.copyOf(evidence);
        lastRejection = lastRejection == null ? "" : lastRejection;
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        resolved = resolved == null ? List.of() : List.copyOf(resolved);
    }

    /** Kept for callers that predate self-raised questions. */
    public InvestigationState(String unitKey, String file, int line, String before, String after,
            List<String> changes, List<Question> open, List<Evidence> evidence, int toolCallsUsed,
            int toolCallsLeft, String lastRejection, List<String> attempts, String facts) {
        this(unitKey, file, line, before, after, changes, open, evidence, toolCallsUsed,
                toolCallsLeft, lastRejection, attempts, facts, false, List.of());
    }

    public List<String> evidenceIds() {
        return evidence.stream().map(Evidence::id).toList();
    }
}
