package com.codereview.agent.investigate;

/**
 * A read-only capability the model may call while investigating one unit.
 *
 * <p>Tools are deliberately few and boring. Each one answers a question a reviewer would actually
 * ask ("what does this line look like now", "who else mentions this name"), and each returns text
 * that can be cited later.
 */
public interface ReviewTool {

    String name();

    String purpose();

    /**
     * Argument names and meaning, in one line, as shown to the model.
     */
    String arguments();

    ToolOutcome run(ToolCall call);
}
