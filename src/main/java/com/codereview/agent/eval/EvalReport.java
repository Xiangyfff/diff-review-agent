package com.codereview.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The evaluation result. Two questions matter and are kept apart on purpose: did triage look at the
 * right place (cheap, model-independent), and did the pipeline end up saying something true at the
 * right line (expensive, model-dependent).
 */
public final class EvalReport {

    /** A finding counts as hitting the injected defect if it lands this close to its line. */
    public static final int LINE_TOLERANCE = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<CaseOutcome> cases;

    public EvalReport(List<CaseOutcome> cases) {
        this.cases = List.copyOf(cases);
    }

    public List<CaseOutcome> cases() {
        return cases;
    }

    public long defective() {
        return cases.stream().filter(CaseOutcome::defective).count();
    }

    public long defectiveScheduled() {
        return cases.stream().filter(c -> c.defective() && c.unitsScheduled() > 0).count();
    }

    public long clean() {
        return cases.stream().filter(c -> !c.defective()).count();
    }

    public long cleanScheduled() {
        return cases.stream().filter(c -> !c.defective() && c.unitsScheduled() > 0).count();
    }

    /**
     * Units the rules alone pushed into MUST_INVESTIGATE. This is the only tier decision that does
     * not depend on a model, so it stays readable while the stand-ins are still in place.
     */
    public long defectiveMust() {
        return cases.stream()
                .filter(c -> c.defective() && "MUST_INVESTIGATE".equals(c.tier()))
                .count();
    }

    public long cleanMust() {
        return cases.stream()
                .filter(c -> !c.defective() && "MUST_INVESTIGATE".equals(c.tier()))
                .count();
    }

    public long defectiveHit() {
        return cases.stream().filter(c -> c.defective() && c.lineMatched()).count();
    }

    public long findings() {
        return cases.stream().mapToLong(CaseOutcome::findings).sum();
    }

    public long kept() {
        return cases.stream().mapToLong(CaseOutcome::kept).sum();
    }

    public long downgraded() {
        return cases.stream().mapToLong(CaseOutcome::downgraded).sum();
    }

    public long dropped() {
        return cases.stream().mapToLong(CaseOutcome::dropped).sum();
    }

    public long merged() {
        return cases.stream().mapToLong(CaseOutcome::merged).sum();
    }

    public long invalidRetractions() {
        return cases.stream().mapToLong(CaseOutcome::invalidRetractions).sum();
    }

    public double triageRecall() {
        return ratio(defectiveScheduled(), defective());
    }

    public double triageFalsePositiveRate() {
        return ratio(cleanScheduled(), clean());
    }

    public double endToEndRecall() {
        return ratio(defectiveHit(), defective());
    }

    public String summary() {
        return "cases=" + cases.size()
                + " defective=" + defective() + " scheduled=" + defectiveScheduled()
                + " triageRecall=" + percent(triageRecall())
                + " | clean=" + clean() + " flagged=" + cleanScheduled()
                + " triageFPR=" + percent(triageFalsePositiveRate())
                + " | findings=" + findings() + " kept=" + kept() + " downgraded=" + downgraded()
                + " dropped=" + dropped() + " merged=" + merged()
                + " invalidRetractions=" + invalidRetractions()
                + " | endToEndRecall=" + percent(endToEndRecall());
    }

    public String renderMarkdown() {
        StringBuilder text = new StringBuilder();
        text.append("# Code review agent — evaluation\n\n");
        text.append("Corpus: ").append(cases.size()).append(" mutations, ")
                .append(defective()).append(" of them introduce a known defect, ")
                .append(clean()).append(" are clean refactors.\n\n");
        text.append("| metric | value |\n|---|---|\n");
        text.append("| triage unit recall | ").append(percent(triageRecall())).append(" (")
                .append(defectiveScheduled()).append('/').append(defective()).append(") |\n");
        text.append("| triage false positive rate | ").append(percent(triageFalsePositiveRate()))
                .append(" (").append(cleanScheduled()).append('/').append(clean()).append(") |\n");
        text.append("| rule-gated recall (MUST, no model involved) | ")
                .append(percent(ratio(defectiveMust(), defective()))).append(" (")
                .append(defectiveMust()).append('/').append(defective()).append(") |\n");
        text.append("| rule-gated false positives (MUST on clean changes) | ")
                .append(percent(ratio(cleanMust(), clean()))).append(" (")
                .append(cleanMust()).append('/').append(clean()).append(") |\n");
        text.append("| end-to-end recall | ").append(percent(endToEndRecall())).append(" (")
                .append(defectiveHit()).append('/').append(defective()).append(") |\n");
        text.append("| findings | ").append(findings()).append(" |\n");
        text.append("| kept / downgraded / dropped | ").append(kept()).append(" / ")
                .append(downgraded()).append(" / ").append(dropped()).append(" |\n");
        text.append("| duplicates merged | ").append(merged()).append(" |\n");
        text.append("| invalid retractions | ").append(invalidRetractions()).append(" |\n\n");
        text.append("> The offline stand-ins never decline. The triage stand-in raises a question "
                + "for every structural change, so every clean case gets scheduled; the verifier "
                + "stand-in retracts as soon as it reads a nearby null check, which is how a real "
                + "defect was dropped below. These rows measure the wiring, not model quality. The "
                + "rule-gated rows above are the part that does not depend on a model at all.\n\n");
        text.append("| case | defective | tier | units | findings | kept | downgraded | dropped "
                + "| merged | invalid | hit line |\n");
        text.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (CaseOutcome outcome : cases) {
            text.append("| ").append(outcome.id())
                    .append(" | ").append(outcome.defective() ? outcome.kind() : "clean")
                    .append(" | ").append(outcome.tier())
                    .append(" | ").append(outcome.unitsScheduled())
                    .append(" | ").append(outcome.findings())
                    .append(" | ").append(outcome.kept())
                    .append(" | ").append(outcome.downgraded())
                    .append(" | ").append(outcome.dropped())
                    .append(" | ").append(outcome.merged())
                    .append(" | ").append(outcome.invalidRetractions())
                    .append(" | ").append(outcome.defective()
                            ? (outcome.lineMatched() ? "yes" : "no") : "-")
                    .append(" |\n");
        }
        return text.toString();
    }

    public String renderJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("summary", summary());
        ObjectNode metrics = root.putObject("metrics");
        metrics.put("cases", cases.size());
        metrics.put("defective", defective());
        metrics.put("defectiveScheduled", defectiveScheduled());
        metrics.put("triageRecall", triageRecall());
        metrics.put("clean", clean());
        metrics.put("cleanScheduled", cleanScheduled());
        metrics.put("triageFalsePositiveRate", triageFalsePositiveRate());
        metrics.put("endToEndRecall", endToEndRecall());
        metrics.put("findings", findings());
        metrics.put("kept", kept());
        metrics.put("downgraded", downgraded());
        metrics.put("dropped", dropped());
        metrics.put("invalidRetractions", invalidRetractions());
        ArrayNode rows = root.putArray("cases");
        for (CaseOutcome outcome : cases) {
            ObjectNode row = rows.addObject();
            row.put("id", outcome.id());
            row.put("defective", outcome.defective());
            row.put("kind", outcome.kind());
            row.put("tier", outcome.tier());
            row.put("unitsScheduled", outcome.unitsScheduled());
            row.put("findings", outcome.findings());
            row.put("kept", outcome.kept());
            row.put("downgraded", outcome.downgraded());
            row.put("dropped", outcome.dropped());
            row.put("invalidRetractions", outcome.invalidRetractions());
            row.put("lineMatched", outcome.lineMatched());
            row.put("anchorLine", outcome.anchorLine());
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("a report built from primitives cannot fail", failure);
        }
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static String percent(double value) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", value * 100);
    }
}
