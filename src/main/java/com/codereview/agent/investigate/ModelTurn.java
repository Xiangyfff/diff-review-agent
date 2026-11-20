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

    record Conclude(String questionId, String statement, List<String> evidenceIds, int line)
            implements ModelTurn {

        public Conclude {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }
}
