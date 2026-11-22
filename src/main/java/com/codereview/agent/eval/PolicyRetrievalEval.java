package com.codereview.agent.eval;

import com.codereview.agent.triage.TriageService;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.plan.TriagePlan;
import com.codereview.agent.triage.policy.Embedder;
import com.codereview.agent.triage.policy.OllamaEmbedder;
import com.codereview.agent.triage.policy.PolicyIndex;
import com.codereview.agent.triage.policy.PolicyLibrary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures retrieval on its own, before anything else uses it.
 *
 * <p>End-to-end recall cannot tell a retrieval problem from an investigation problem: a clause that
 * never comes back looks exactly like a clause the model ignored. So each case that has an
 * acceptable clause is given one here, and the question is only "which rank did it come back at".
 *
 * <p>The acceptable set is deliberately broad where two clauses both fit: printing a password is
 * both a logging violation and a secrecy violation, and demanding one specific winner would measure
 * luck rather than retrieval.
 *
 * <p>One honest limit: the mapping below is written by whoever wrote the corpus. It is a labelled
 * sample, not ground truth from a security team.
 */
public final class PolicyRetrievalEval {

    private static final Pattern CLAUSE_ID = Pattern.compile("pol-[A-Za-z0-9-]+");

    /** case id → clause ids any of which would be a correct hit. */
    private static final Map<String, Set<String>> EXPECTED = Map.of(
            "case-insensitive-password", Set.of("pol-security-2"),
            "password-content-equals", Set.of("pol-security-2"),
            "policy-plain-equals", Set.of("pol-security-2"),
            "password-logged", Set.of("pol-security-1", "pol-logging-1"),
            "policy-stdout-in-audit", Set.of("pol-logging-1"));

    private PolicyRetrievalEval() {
    }

    public static void main(String[] args) throws Exception {
        Path workspace = args.length > 0 ? Path.of(args[0]) : Path.of("target/eval-corpus");
        CorpusRepository.Corpus corpus = CorpusRepository.build(workspace);
        PolicyLibrary library = PolicyLibrary.load(corpus.workspace().resolve("docs/policies"));
        Embedder embedder = OllamaEmbedder.fromEnvironment();
        System.out.println("clauses: " + library.chunks().size() + ", labelled cases: " + EXPECTED.size());
        for (PolicyIndex.Scoring scoring : PolicyIndex.Scoring.values()) {
            PolicyIndex index = PolicyIndex.build(library, embedder, scoring);
            System.out.println();
            System.out.println("== " + scoring + " (clauses=" + index.size()
                    + ", embed failures=" + index.embedFailures() + ")");
            measure(corpus, index);
        }
    }

    private static void measure(CorpusRepository.Corpus corpus, PolicyIndex index)
            throws java.io.IOException {
        TriageService triage = TriageService.offline().withPolicies(index);
        int labelled = 0;
        int top1 = 0;
        int top3 = 0;
        int rankSum = 0;
        int unlabelledWithHits = 0;
        int unlabelled = 0;
        String previous = corpus.base();
        for (CorpusRepository.Commit commit : corpus.commits()) {
            List<String> ids = retrieve(triage, corpus.workspace(), previous, commit.commitId());
            previous = commit.commitId();
            Set<String> expected = EXPECTED.get(commit.caseId());
            if (expected == null) {
                unlabelled++;
                if (!ids.isEmpty()) {
                    unlabelledWithHits++;
                }
                continue;
            }
            labelled++;
            int rank = rankOf(ids, expected);
            if (rank == 1) {
                top1++;
            }
            if (rank >= 1 && rank <= 3) {
                top3++;
                rankSum += rank;
            }
            System.out.println(String.format("  %-26s %s rank=%s%s", commit.caseId(),
                    expected, rank < 0 ? "miss" : String.valueOf(rank),
                    ids.isEmpty() ? " (nothing cleared the cut)" : " hits=" + ids));
        }
        System.out.println(String.format(
                "  labelled=%d top1=%d top3=%d meanRank=%.2f | unlabelled=%d anyHit=%d",
                labelled, top1, top3, top3 == 0 ? 0.0 : (double) rankSum / top3,
                unlabelled, unlabelledWithHits));
    }

    /** The clause ids that came back for one commit, best rank first, duplicates removed. */
    private static List<String> retrieve(TriageService triage, Path workspace, String base, String head)
            throws java.io.IOException {
        TriagePlan plan = triage.triage(workspace, base, head);
        Set<String> ids = new LinkedHashSet<>();
        for (PlannedUnit unit : plan.scheduled()) {
            Matcher matcher = CLAUSE_ID.matcher(unit.facts());
            while (matcher.find()) {
                ids.add(matcher.group());
            }
        }
        return new ArrayList<>(ids);
    }

    private static int rankOf(List<String> ids, Set<String> expected) {
        for (int i = 0; i < ids.size(); i++) {
            if (expected.contains(ids.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }
}
