package com.codereview.agent;

import com.codereview.agent.investigate.Finding;
import com.codereview.agent.investigate.InvestigateConfig;
import com.codereview.agent.investigate.InvestigationResult;
import com.codereview.agent.investigate.InvestigationRunner;
import com.codereview.agent.investigate.InvestigationTask;
import com.codereview.agent.investigate.MockInvestigationModel;
import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.Toolbox;
import com.codereview.agent.triage.TriageService;
import com.codereview.agent.triage.index.IndexBuilder;
import com.codereview.agent.triage.index.SymbolIndex;
import com.codereview.agent.triage.parse.SourceParser;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.plan.TriagePlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs triage and then the investigate loop over the scheduled units, offline, and writes what it
 * found. This exists to make the whole pipeline observable on a real repository without an API key.
 */
public final class InvestigateDemo {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InvestigateDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: InvestigateDemo <repository> <base> <head> <output.json>");
            System.exit(2);
        }
        Path repository = Path.of(args[0]).toAbsolutePath().normalize();
        String base = args[1];
        String head = args[2];
        Path output = Path.of(args[3]);

        TriagePlan plan = TriageService.offline().triage(repository, base, head);
        SymbolIndex index = new IndexBuilder(new SourceParser()).build(repository).index();
        RepoAccess access = new RepoAccess(repository, index);
        InvestigationRunner runner = new InvestigationRunner(new MockInvestigationModel(),
                Toolbox.standard(access), InvestigateConfig.defaults());

        ObjectNode root = MAPPER.createObjectNode();
        root.put("repository", repository.toString());
        root.put("base", base);
        root.put("head", head);
        ArrayNode units = root.putArray("units");
        int findings = 0;
        for (PlannedUnit unit : plan.scheduled()) {
            InvestigationResult result = runner.investigate(taskOf(unit));
            findings += result.findings().size();
            units.add(render(unit, result));
        }
        root.put("unitsInvestigated", plan.scheduled().size());
        root.put("findings", findings);

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        System.out.println("demo written to " + output + " (" + plan.scheduled().size()
                + " unit(s), " + findings + " finding(s))");
    }

    private static InvestigationTask taskOf(PlannedUnit unit) {
        return new InvestigationTask(
                unit.unit().key(),
                unit.unit().file(),
                unit.unit().after() == null ? 1 : unit.unit().after().startLine(),
                unit.unit().textBefore(),
                unit.unit().textAfter(),
                unit.changes().stream().map(change -> change.describe()).toList(),
                unit.questions().stream().map(question -> question.text()).toList());
    }

    private static ObjectNode render(PlannedUnit unit, InvestigationResult result) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("unit", unit.unit().key());
        node.put("file", unit.unit().file());
        node.put("outcome", result.outcome().name());
        node.put("toolCalls", result.toolCalls());
        node.put("turns", result.turns());
        node.put("rejectedFindings", result.rejectedFindings());
        ArrayNode found = node.putArray("findings");
        for (Finding finding : result.findings()) {
            ObjectNode entry = found.addObject();
            entry.put("line", finding.line());
            entry.put("statement", finding.statement());
            ArrayNode cited = entry.putArray("evidence");
            finding.evidenceIds().forEach(cited::add);
        }
        ArrayNode abandoned = node.putArray("abandoned");
        for (InvestigationResult.Abandoned item : result.abandoned()) {
            ObjectNode entry = abandoned.addObject();
            entry.put("question", item.question());
            entry.put("reason", item.reason());
        }
        return node;
    }
}
