package com.codereview.agent.eval;

import com.codereview.agent.triage.TriageService;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.plan.TriagePlan;
import com.codereview.agent.triage.policy.OllamaEmbedder;
import com.codereview.agent.triage.policy.PolicyHit;
import com.codereview.agent.triage.policy.PolicyIndex;
import com.codereview.agent.triage.policy.PolicyLibrary;
import com.codereview.agent.triage.policy.PolicyQuery;

import java.nio.file.Path;
import java.util.List;

/**
 * Prints every clause score for one commit, so a retrieval miss can be explained instead of
 * guessed at. Reads like a debugger for the numbers in {@code eval/latest-retrieval.md}.
 */
public final class PolicyScoreProbe {

    private PolicyScoreProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args.length > 0 ? args[0] : "target/eval-corpus");
        String caseId = args.length > 1 ? args[1] : "case-insensitive-password";
        CorpusRepository.Corpus corpus = CorpusRepository.build(workspace);
        PolicyLibrary library = PolicyLibrary.load(corpus.workspace().resolve("docs/policies"));
        PolicyIndex.Scoring scoring = PolicyIndex.Scoring.valueOf(
                args.length > 2 ? args[2] : "SAMPLES");
        PolicyIndex index = PolicyIndex.build(library, OllamaEmbedder.fromEnvironment(), scoring);
        System.out.println("scoring " + scoring);
        String previous = corpus.base();
        for (CorpusRepository.Commit commit : corpus.commits()) {
            String base = previous;
            previous = commit.commitId();
            if (!commit.caseId().equals(caseId)) {
                continue;
            }
            System.out.println("case " + caseId + " " + base.substring(0, 7) + ".."
                    + commit.commitId().substring(0, 7));
            TriagePlan plan = TriageService.offline().withPolicies(index)
                    .triage(corpus.workspace(), base, commit.commitId());
            for (PlannedUnit unit : plan.units()) {
                if (unit.changes().isEmpty()) {
                    continue;
                }
                String query = PolicyQuery.of(unit.unit(), unit.changes());
                System.out.println("unit " + unit.unit().key() + " at line "
                        + unit.unit().after().startLine() + " tier " + unit.tier());
                System.out.println("  query: " + query.replaceAll("\\s+", " "));
                List<String> gated = new java.util.ArrayList<>();
                for (PolicyHit gatedHit : index.search(query, 3, 0.55)) {
                    gated.add(gatedHit.chunk().id());
                }
                System.out.println("  pipeline sees: " + gated);
                System.out.println("  facts: " + unit.facts().replaceAll("\\s+", " "));
                for (String line : index.explainScores(PolicyQuery.prose(unit.unit(), unit.changes()),
                        PolicyQuery.code(unit.unit()))) {
                    System.out.println("    " + line);
                }
                List<PolicyHit> hits = index.search(query, 99, 0.0);
                for (PolicyHit hit : hits) {
                    System.out.println(String.format("    %.3f %s | %s",
                            hit.score(), hit.chunk().id(), hit.chunk().heading()));
                }
            }
        }
    }
}
