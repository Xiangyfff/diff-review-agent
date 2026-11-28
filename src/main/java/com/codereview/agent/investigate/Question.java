package com.codereview.agent.investigate;

/**
 * One question the investigation stage is trying to answer.
 *
 * <p>Questions are raised by the model itself (or seeded by a rule when the cheap stage demanded an
 * answer), then scheduled by the runner: lowest priority number first, then the order they were
 * raised in. The model does not get to pick which question comes next.
 *
 * @param priority 1 is most urgent, 3 least; the runner picks the smallest
 * @param depth    how many questions deep this one is, so a chain of "and what about…" is bounded
 * @param toolsUsed how many tool calls have been spent on this question alone
 */
public record Question(String id, String text, int priority, int depth, int toolsUsed) {

    public Question {
        priority = Math.max(1, Math.min(3, priority));
        depth = Math.max(0, depth);
        toolsUsed = Math.max(0, toolsUsed);
    }

    /** Kept for callers that predate priorities. */
    public Question(String id, String text) {
        this(id, text, 2, 0, 0);
    }

    public Question withToolUse() {
        return new Question(id, text, priority, depth, toolsUsed + 1);
    }
}
