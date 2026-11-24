package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;
import com.codereview.agent.triage.policy.PolicyChunk;
import com.codereview.agent.triage.policy.PolicyHit;
import com.codereview.agent.triage.policy.PolicyIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the policy clauses that talk about what the investigator is looking at.
 *
 * <p>The query is the investigator's own words, which is the one place an abstraction is worth its
 * cost: it is written when the model is stuck on a specific question, not for every unit up front,
 * and the model can search again if the first phrasing misses.
 */
public final class SearchPolicyTool implements ReviewTool {

    private static final int DEFAULT_LIMIT = 3;
    private static final int MAX_LIMIT = 5;
    private static final double CUT = 0.45;

    private final PolicyIndex policies;

    public SearchPolicyTool(PolicyIndex policies) {
        this.policies = policies;
    }

    @Override
    public String name() {
        return "search_policy";
    }

    @Override
    public String purpose() {
        return "Find the repository's own policy clauses about a topic, by a one-sentence query.";
    }

    @Override
    public String arguments() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"一句话，例如 口令比较 恒定时间\"},"
                + "\"limit\":{\"type\":\"integer\",\"description\":\"最多返回几条，默认 3\"}},"
                + "\"required\":[\"query\"]}";
    }

    @Override
    public ToolOutcome run(ToolCall call) {
        String query = call.argument("query");
        if (query.isBlank()) {
            return ToolOutcome.failed("query is empty");
        }
        if (policies.isEmpty()) {
            return ToolOutcome.failed("this repository has no policy documents under docs/policies");
        }
        int limit = Math.min(MAX_LIMIT, Math.max(1, call.intArgument("limit", DEFAULT_LIMIT)));
        List<PolicyHit> hits = policies.search(query, limit, CUT);
        if (hits.isEmpty()) {
            return ToolOutcome.of("no policy clause scored above " + CUT + " for that query", List.of());
        }
        List<ToolOutcome.Excerpt> excerpts = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        for (PolicyHit hit : hits) {
            PolicyChunk chunk = hit.chunk();
            summary.add(String.format("%s (%.2f, %s)", chunk.id(), hit.score(), chunk.heading()));
            excerpts.add(new ToolOutcome.Excerpt("<policy>", 0, 0,
                    chunk.cite() + "\n" + chunk.excerpt(300)));
        }
        return ToolOutcome.of("clauses: " + String.join("; ", summary), excerpts);
    }
}
