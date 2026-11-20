package com.codereview.agent.triage.plan;

import com.codereview.agent.triage.model.FindingQuestion;
import com.codereview.agent.triage.rules.RuleHit;
import com.codereview.agent.triage.structure.StructuralChange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Renders a plan as JSON.
 *
 * <p>Hand-written rather than annotated on the records: the plan holds whole source texts
 * inside its units, and a generic serializer would happily copy every changed method into the
 * output. What leaves triage is the evidence the next stage needs, not the input again.
 */
public final class TriagePlanJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public String render(TriagePlan plan) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("baseRevision", plan.baseRevision());
        root.put("headRevision", plan.headRevision());

        ObjectNode intent = root.putObject("intent");
        intent.put("summary", plan.intent().intent());
        ArrayNode concerns = intent.putArray("crossFileConcerns");
        plan.intent().crossFileConcerns().forEach(concerns::add);

        ArrayNode units = root.putArray("units");
        for (PlannedUnit unit : plan.units()) {
            units.add(renderUnit(unit));
        }

        ObjectNode stats = root.putObject("stats");
        stats.put("filesSeen", plan.stats().filesSeen());
        stats.put("filesScreenedOut", plan.stats().filesScreenedOut());
        stats.put("unitsTotal", plan.stats().unitsTotal());
        stats.put("unitsMust", plan.stats().unitsMust());
        stats.put("unitsJudge", plan.stats().unitsJudge());
        stats.put("unitsSkip", plan.stats().unitsSkip());
        stats.put("unitsScheduled", plan.stats().unitsScheduled());
        stats.put("unitsBudgetSkipped", plan.stats().unitsBudgetSkipped());
        stats.put("cheapGateSkipped", plan.cheapGateSkipped());
        stats.put("invalidQuestions", plan.stats().invalidQuestions());
        stats.put("calibrationDisagreements", plan.stats().calibrationDisagreements());
        stats.put("unparsedUnits", plan.stats().unparsedUnits());
        stats.put("modelCalls", plan.stats().modelCalls());
        stats.put("investigateRatio", round(plan.stats().investigateRatio()));
        stats.put("summary", plan.stats().summary());

        try {
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("a plan built from strings cannot fail to serialise", failure);
        }
    }

    private ObjectNode renderUnit(PlannedUnit unit) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tier", unit.tier().name());
        node.put("riskCategory", unit.category().name());
        node.put("tierReason", unit.tierReason());
        node.put("file", unit.unit().file());
        node.put("name", unit.unit().name());
        node.put("kind", unit.unit().kind().name());
        node.put("key", unit.unit().key());
        node.put("line", unit.unit().startLine());
        node.put("addedLines", unit.unit().addedLines());
        node.put("removedLines", unit.unit().removedLines());
        node.put("scheduled", unit.scheduled());
        node.put("budgetSkipped", unit.budgetSkipped());
        node.put("parseFailed", unit.parseFailed());
        node.put("syntheticQuestions", unit.syntheticQuestions());
        if (unit.abstainReason() != null && !unit.abstainReason().isBlank()) {
            node.put("abstainReason", unit.abstainReason());
        }
        if (unit.facts() != null && !unit.facts().isBlank()) {
            node.put("facts", unit.facts());
        }

        ArrayNode changes = node.putArray("changes");
        for (StructuralChange change : unit.changes()) {
            ObjectNode changeNode = changes.addObject();
            changeNode.put("kind", change.kind().name());
            changeNode.put("confidence", change.confidence().name());
            changeNode.put("line", change.line());
            changeNode.put("detail", change.detail());
            changeNode.put("before", change.beforeSnippet());
            changeNode.put("after", change.afterSnippet());
        }

        ArrayNode rules = node.putArray("rules");
        for (RuleHit hit : unit.ruleHits()) {
            ObjectNode ruleNode = rules.addObject();
            ruleNode.put("id", hit.rule().id());
            ruleNode.put("category", hit.rule().category().name());
            ruleNode.put("mandatory", hit.isMandatory());
            ruleNode.put("rationale", hit.rule().rationale());
            ruleNode.put("evidence", hit.evidence().size());
        }

        ArrayNode questions = node.putArray("questions");
        for (FindingQuestion question : unit.questions()) {
            ObjectNode questionNode = questions.addObject();
            questionNode.put("target", question.target());
            questionNode.put("text", question.text());
        }
        return node;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
