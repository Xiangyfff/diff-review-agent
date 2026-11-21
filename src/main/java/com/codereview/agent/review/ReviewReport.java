package com.codereview.agent.review;

import com.codereview.agent.triage.plan.TriageStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The end of the pipeline: what was reviewed, what was left out, and which comments survived.
 *
 * <p>Serialised by hand rather than by annotations for the same reason the triage plan is: the
 * intermediate objects carry whole method bodies, and a generic serialiser would copy every
 * changed method into the output.
 */
public record ReviewReport(String baseRevision, String headRevision, TriageStats stats,
        List<UnitOutcome> units, List<Comment> comments, int findings, int dropped,
        int invalidRetractions, int merged) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ReviewReport {
        units = List.copyOf(units);
        comments = List.copyOf(comments);
    }

    public int assertions() {
        return (int) comments.stream().filter(comment -> comment.status() == Comment.Status.ASSERTION)
                .count();
    }

    public int questions() {
        return comments.size() - assertions();
    }

    public String renderMarkdown() {
        StringBuilder out = new StringBuilder();
        out.append("# Review ").append(baseRevision).append("..").append(headRevision).append("\n\n");
        out.append("Units investigated: ").append(units.size())
                .append(" | comments: ").append(comments.size())
                .append(" (assertions ").append(assertions())
                .append(", questions ").append(questions()).append(")")
                .append(" | findings rejected by the verifier: ").append(dropped)
                .append(" | retractions without evidence (kept): ").append(invalidRetractions)
                .append(" | duplicates merged: ").append(merged)
                .append("\n\n");

        if (!units.isEmpty()) {
            out.append("| file | unit | tier | category | outcome | tool calls | findings |\n");
            out.append("|---|---|---|---|---|---|---|\n");
            for (UnitOutcome unit : units) {
                out.append("| ").append(unit.file())
                        .append(" | ").append(unit.unitKey())
                        .append(" | ").append(unit.tier())
                        .append(" | ").append(unit.riskCategory())
                        .append(" | ").append(unit.outcome())
                        .append(" | ").append(unit.toolCalls())
                        .append(" | ").append(unit.findings())
                        .append(" |\n");
            }
            out.append('\n');
        }

        out.append("## Comments\n\n");
        if (comments.isEmpty()) {
            out.append("Nothing survived verification.\n");
        }
        for (Comment comment : comments) {
            out.append("- ").append(comment.render()).append('\n');
        }
        return out.toString();
    }

    public String renderJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("baseRevision", baseRevision);
        root.put("headRevision", headRevision);
        // Counts and lists need different names: the lists below would otherwise overwrite
        // the numbers, and a client reading a number would find an array.
        root.put("unitCount", units.size());
        root.put("commentCount", comments.size());
        root.put("assertions", assertions());
        root.put("questions", questions());
        root.put("findings", findings);
        root.put("dropped", dropped);
        root.put("invalidRetractions", invalidRetractions);
        root.put("merged", merged);

        ArrayNode unitNodes = root.putArray("unitOutcomes");
        for (UnitOutcome unit : units) {
            ObjectNode node = unitNodes.addObject();
            node.put("file", unit.file());
            node.put("unitKey", unit.unitKey());
            node.put("tier", unit.tier());
            node.put("riskCategory", unit.riskCategory());
            node.put("outcome", unit.outcome());
            node.put("toolCalls", unit.toolCalls());
            node.put("findings", unit.findings());
            node.put("verifyCalls", unit.verifyCalls());
            ArrayNode abandoned = node.putArray("abandoned");
            unit.abandoned().forEach(abandoned::add);
        }

        ArrayNode commentNodes = root.putArray("comments");
        for (Comment comment : comments) {
            ObjectNode node = commentNodes.addObject();
            node.put("file", comment.file());
            node.put("line", comment.line());
            node.put("riskCategory", comment.riskCategory());
            node.put("status", comment.status().name());
            node.put("body", comment.body());
            ArrayNode evidence = node.putArray("evidence");
            comment.evidenceIds().forEach(evidence::add);
        }
        return root.toPrettyString();
    }
}
