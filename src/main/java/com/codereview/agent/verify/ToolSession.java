package com.codereview.agent.verify;

import com.codereview.agent.investigate.Evidence;
import com.codereview.agent.investigate.EvidenceStore;
import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;
import com.codereview.agent.investigate.Toolbox;

import java.util.List;
import java.util.Optional;

/**
 * The critic's view of the repository: the same read-only tools as the investigator, but with its
 * own evidence store and its own, smaller, call budget. Keeping the stores apart matters because a
 * retraction must cite evidence the critic collected while criticising, not evidence the
 * investigator collected while asserting.
 */
public final class ToolSession {

    private final Toolbox toolbox;
    private final EvidenceStore store = new EvidenceStore();
    private final int maxCalls;
    private int calls;

    public ToolSession(Toolbox toolbox, int maxCalls) {
        this.toolbox = toolbox;
        this.maxCalls = maxCalls;
    }

    public ToolOutcome run(ToolCall call) {
        if (call == null) {
            return ToolOutcome.failed("no tool call was given");
        }
        Optional<ReviewTool> tool = toolbox.find(call.tool());
        if (tool.isEmpty()) {
            return ToolOutcome.failed("no such tool: " + call.tool());
        }
        if (calls >= maxCalls) {
            return ToolOutcome.failed("tool budget exhausted: no more calls this round");
        }
        calls++;
        ToolOutcome outcome = tool.get().run(call);
        for (ToolOutcome.Excerpt excerpt : outcome.excerpts()) {
            store.add(call.tool(), excerpt.file(), excerpt.startLine(), excerpt.endLine(),
                    excerpt.text());
        }
        return outcome;
    }

    public List<Evidence> evidence() {
        return store.all();
    }

    public int calls() {
        return calls;
    }

    public int callsLeft() {
        return Math.max(0, maxCalls - calls);
    }
}
