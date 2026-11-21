package com.codereview.agent.investigate;

import java.util.List;

/**
 * One decision the model makes per turn: call a tool, give up on a question, or conclude.
 *
 * <p>Concluding is explicitly tied to a question, because a conclusion nobody asked for is how a
 * reviewer ends up with observations instead of answers.
 */
public sealed interface ModelTurn {

    record CallTool(String questionId, ToolCall call) implements ModelTurn {
    }

    record Abandon(String questionId, String reason) implements ModelTurn {
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
