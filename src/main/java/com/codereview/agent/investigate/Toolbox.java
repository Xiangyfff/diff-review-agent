package com.codereview.agent.investigate;

import com.codereview.agent.investigate.tool.FindCallersTool;
import com.codereview.agent.investigate.tool.FindDefinitionTool;
import com.codereview.agent.investigate.tool.FindReferencesTool;
import com.codereview.agent.investigate.tool.HistoryTool;
import com.codereview.agent.investigate.tool.ReadLinesTool;
import com.codereview.agent.investigate.tool.SearchTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tools one investigation may use, keyed by the name the model is told to call.
 *
 * <p>The set is fixed per run. A model that invents a tool name gets an error message, not a
 * crash, because that is a normal failure mode of models, not an exceptional condition.
 */
public final class Toolbox {

    private final Map<String, ReviewTool> tools;

    public Toolbox(List<ReviewTool> tools) {
        Map<String, ReviewTool> byName = new LinkedHashMap<>();
        for (ReviewTool tool : tools) {
            byName.put(tool.name(), tool);
        }
        this.tools = Map.copyOf(byName);
    }

    public static Toolbox standard(RepoAccess repository) {
        return new Toolbox(List.of(
                cached(new ReadLinesTool(repository)),
                cached(new FindCallersTool(repository)),
                cached(new FindDefinitionTool(repository)),
                cached(new FindReferencesTool(repository)),
                cached(new SearchTool(repository)),
                cached(new HistoryTool(repository))));
    }

    /**
     * The repository does not move during a review, so asking it the same question twice can only
     * produce the same answer. Caching here saves a tool call from being re-executed, but it does
     * not make a repeated call look productive: the runner detects the duplicate request itself and
     * counts it as no new information.
     */
    private static ReviewTool cached(ReviewTool tool) {
        Map<String, ToolOutcome> memory = new ConcurrentHashMap<>();
        return new ReviewTool() {
            @Override
            public String name() {
                return tool.name();
            }

            @Override
            public String purpose() {
                return tool.purpose();
            }

            @Override
            public String arguments() {
                return tool.arguments();
            }

            @Override
            public ToolOutcome run(ToolCall call) {
                String key = tool.name() + "|" + new TreeMap<>(call.arguments());
                return memory.computeIfAbsent(key, ignored -> tool.run(call));
            }
        };
    }

    public Optional<ReviewTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ReviewTool> all() {
        return List.copyOf(tools.values());
    }

    /**
     * The tool list as it appears in the prompt: name, purpose and arguments, one per line.
     */
    public String render() {
        StringBuilder text = new StringBuilder();
        for (ReviewTool tool : tools.values()) {
            text.append(tool.name()).append(" — ").append(tool.purpose())
                    .append("  arguments: ").append(tool.arguments()).append('\n');
        }
        return text.toString().stripTrailing();
    }
}
