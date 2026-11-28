package com.codereview.agent.investigate;

import java.util.List;

/**
 * One decision the model makes per turn: raise questions, call a tool, reject a question, give up,
 * conclude, or exempt.
 *
 * <p>Every answer is explicitly tied to a question, because a conclusion nobody asked for is how a
 * reviewer ends up with observations instead of answers.
 */
public sealed interface ModelTurn {

    record CallTool(String questionId, ToolCall call) implements ModelTurn {
    }

    /**
     * A question the model wants to add to the queue. Raising is only allowed at the start of an
     * investigation, or one at a time while it is running; the runner decides whether it is
     * anchored enough to keep.
     */
    record Raise(List<Seed> questions) implements ModelTurn {

        public Raise {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    /** A question the model read code for and now believes is not a problem. */
    record Reject(String questionId, String reason, List<String> evidenceIds) implements ModelTurn {

        public Reject {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    /** One proposed question, before the runner assigns it an id. */
    record Seed(String target, String text, int priority) {

        public Seed {
            target = target == null ? "" : target.strip();
            text = text == null ? "" : text.strip();
            priority = Math.max(1, Math.min(3, priority));
        }
    }

    record Abandon(String questionId, String reason) implements ModelTurn {
    }

    /**
     * "The policy already allows this change." This is a conclusion-shaped act, not a tool result:
     * the model has to name the clause, quote its wording, and cite the code it read that satisfies
     * the clause. The runner checks all three before the question may close.
     */
    record Exempt(String questionId, String clause, String quote, String reason,
            List<String> evidenceIds, int line) implements ModelTurn {

        public Exempt {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    record Conclude(String questionId, String statement, List<String> evidenceIds, int line,
            /** What the author is expected to do: {@code fix} or {@code confirm}. */
            String demand) implements ModelTurn {

        public static final String FIX = "fix";
        public static final String CONFIRM = "confirm";

        public Conclude {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            demand = demand == null ? FIX : demand.strip().toLowerCase(java.util.Locale.ROOT);
        }

        /** Kept for callers that do not distinguish the two kinds of conclusion. */
        public Conclude(String questionId, String statement, List<String> evidenceIds, int line) {
            this(questionId, statement, evidenceIds, line, FIX);
        }
    }
}
