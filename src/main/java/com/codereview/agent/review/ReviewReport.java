package com.codereview.agent.review;

import com.codereview.agent.triage.plan.TriageStats;
import com.codereview.agent.triage.screen.ScreenedFile;
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
        int invalidRetractions, int merged, List<ScreenedFile> screenedOut, int verified,
        int downgraded, Sampling.Summary sampling) {

    public ReviewReport(String baseRevision, String headRevision, TriageStats stats,
            List<UnitOutcome> units, List<Comment> comments, int findings, int dropped,
            int invalidRetractions, int merged, List<ScreenedFile> screenedOut, int verified,
            int downgraded) {
        this(baseRevision, headRevision, stats, units, comments, findings, dropped,
                invalidRetractions, merged, screenedOut, verified, downgraded,
                Sampling.Summary.none());
    }

    public ReviewReport(String baseRevision, String headRevision, TriageStats stats,
            List<UnitOutcome> units, List<Comment> comments, int findings, int dropped,
            int invalidRetractions, int merged, List<ScreenedFile> screenedOut) {
        this(baseRevision, headRevision, stats, units, comments, findings, dropped,
                invalidRetractions, merged, screenedOut, 0, 0, Sampling.Summary.none());
    }

    public ReviewReport(String baseRevision, String headRevision, TriageStats stats,
            List<UnitOutcome> units, List<Comment> comments, int findings, int dropped,
            int invalidRetractions, int merged) {
        this(baseRevision, headRevision, stats, units, comments, findings, dropped,
                invalidRetractions, merged, List.of(), 0, 0);
    }

    /**
     * How many findings the critic actually saw, and how many of those it changed its mind about.
     * A critic that never changes anything is a cost with no measured benefit, and saying so is
     * more useful than leaving the reader to infer it from a zero.
     */
    public int changedByCritic() {
        return dropped + downgraded;
    }

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
                .append(" | findings the critic saw: ").append(verified)
                .append(" | findings rejected by the verifier: ").append(dropped)
                .append(" | findings downgraded to questions: ").append(downgraded)
                .append(" | retractions without evidence (kept): ").append(invalidRetractions)
                .append(" | duplicates merged: ").append(merged)
                .append("\n");
        if (sampling.rate() > 0) {
            out.append(sampling.describe()).append('\n');
        }
        out.append('\n');

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
                        .append(unit.sampled() ? " (抽查)" : "")
                        .append(unit.policies().isEmpty() ? "" : " " + String.join(",", unit.policies()))
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
        root.put("verified", verified);
        root.put("downgraded", downgraded);
        root.put("changedByCritic", changedByCritic());
        root.put("invalidRetractions", invalidRetractions);
        root.put("merged", merged);
        ObjectNode samplingNode = root.putObject("sampling");
        samplingNode.put("rate", sampling.rate());
        samplingNode.put("percent", Math.round(sampling.rate() * 100));
        samplingNode.put("candidates", sampling.candidates());
        samplingNode.put("sampled", sampling.sampled());
        samplingNode.put("withFindings", sampling.withFindings());
        samplingNode.put("estimate", Math.round(sampling.estimate() * 1000) / 1000.0);
        samplingNode.put("summary", sampling.describe());
        ArrayNode skipped = root.putArray("screenedOut");
        for (ScreenedFile file : screenedOut) {
            ObjectNode node = skipped.addObject();
            node.put("path", file.path());
            node.put("reason", file.reason());
        }

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
            node.put("sampled", unit.sampled());
            ArrayNode policies = node.putArray("policies");
            unit.policies().forEach(policies::add);
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
