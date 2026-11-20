package com.codereview.agent.investigate;

import com.codereview.agent.investigate.tool.FindDefinitionTool;
import com.codereview.agent.investigate.tool.FindReferencesTool;
import com.codereview.agent.investigate.tool.HistoryTool;
import com.codereview.agent.investigate.tool.ReadLinesTool;
import com.codereview.agent.investigate.tool.SearchTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                new ReadLinesTool(repository),
                new FindDefinitionTool(repository),
                new FindReferencesTool(repository),
                new SearchTool(repository),
                new HistoryTool(repository)));
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
