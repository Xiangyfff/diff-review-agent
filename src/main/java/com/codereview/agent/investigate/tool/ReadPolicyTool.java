package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;
import com.codereview.agent.triage.policy.PolicyChunk;
import com.codereview.agent.triage.policy.PolicyIndex;

import java.util.List;
import java.util.Optional;

/**
 * Reads one policy clause in full, because an excerpt is where the exceptions get cut off.
 */
public final class ReadPolicyTool implements ReviewTool {

    private static final int MAX_TEXT = 1500;

    private final PolicyIndex policies;

    public ReadPolicyTool(PolicyIndex policies) {
        this.policies = policies;
    }

    @Override
    public String name() {
        return "read_policy";
    }

    @Override
    public String purpose() {
        return "Read one policy clause in full by its id, including any exceptions it states.";
    }

    @Override
    public String arguments() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"id\":{\"type\":\"string\",\"description\":\"条款编号，例如 pol-security-2\"}},"
                + "\"required\":[\"id\"]}";
    }

    @Override
    public ToolOutcome run(ToolCall call) {
        String id = call.argument("id").strip();
        if (id.isEmpty()) {
            return ToolOutcome.failed("id is empty");
        }
        Optional<PolicyChunk> chunk = policies.chunks().stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst();
        if (chunk.isEmpty()) {
            return ToolOutcome.failed("no policy clause with id " + id);
        }
        PolicyChunk clause = chunk.get();
        String text = clause.fullText();
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT) + "...";
        }
        String body = clause.cite() + " (mandatory=" + clause.mandatory() + ")\n" + text;
        return ToolOutcome.of("clause " + id, List.of(new ToolOutcome.Excerpt("<policy>", 0, 0, body)));
    }
}
