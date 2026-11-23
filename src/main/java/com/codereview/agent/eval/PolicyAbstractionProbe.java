package com.codereview.agent.eval;

import com.codereview.agent.model.Chat;
import com.codereview.agent.model.ModelSettings;
import com.codereview.agent.triage.TriageService;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.plan.TriagePlan;
import com.codereview.agent.triage.policy.OllamaEmbedder;
import com.codereview.agent.triage.policy.PolicyHit;
import com.codereview.agent.triage.policy.PolicyIndex;
import com.codereview.agent.triage.policy.PolicyLibrary;
import com.codereview.agent.triage.structure.StructuralChange;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Would an abstraction written by the model retrieve the right clause? Five labelled cases, five
 * calls, a few cents — cheaper than arguing about it.
 *
 * <p>The comparison is prose-only on purpose: clauses with samples are scored on their prose here,
 * because the question is what the words alone can do.
 */
public final class PolicyAbstractionProbe {

    private static final Map<String, Set<String>> EXPECTED = Map.of(
            "case-insensitive-password", Set.of("pol-security-2"),
            "password-content-equals", Set.of("pol-security-2"),
            "policy-plain-equals", Set.of("pol-security-2"),
            "password-logged", Set.of("pol-security-1", "pol-logging-1"),
            "policy-stdout-in-audit", Set.of("pol-logging-1"));

    private static final String SYSTEM = """
            你是代码审查助手。把一处改动概括成一句中文，说明它改变了什么行为。
            不要出现代码，不要出现方法名、类名、变量名。只输出这句话，不要解释。
            """;

    private PolicyAbstractionProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args.length > 0 ? args[0] : "target/eval-corpus");
        CorpusRepository.Corpus corpus = CorpusRepository.build(workspace);
        PolicyLibrary library = PolicyLibrary.load(corpus.workspace().resolve("docs/policies"));
        PolicyIndex index = PolicyIndex.build(library, OllamaEmbedder.fromEnvironment(),
                PolicyIndex.Scoring.EMBEDDING_ONLY);
        Chat chat = Chat.openAiCompatiblePlain(ModelSettings.fromEnvironment());
        StringBuilder log = new StringBuilder();
        log.append("model: ").append(ModelSettings.fromEnvironment().describe()).append('\n');
        int top1 = 0;
        int top3 = 0;
        String previous = corpus.base();
        for (CorpusRepository.Commit commit : corpus.commits()) {
            String base = previous;
            previous = commit.commitId();
            Set<String> expected = EXPECTED.get(commit.caseId());
            if (expected == null) {
                continue;
            }
            TriagePlan plan = TriageService.offline().triage(corpus.workspace(), base, commit.commitId());
            PlannedUnit target = plan.units().stream()
                    .filter(unit -> !unit.changes().isEmpty())
                    .findFirst().orElse(null);
            if (target == null) {
                System.out.println(commit.caseId() + ": no unit with changes");
                continue;
            }
            String abstraction = chat.complete(SYSTEM, user(target));
            List<PolicyHit> hits = index.search(abstraction, 5, 0.0);
            int rank = -1;
            for (int i = 0; i < hits.size(); i++) {
                if (expected.contains(hits.get(i).chunk().id())) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank == 1) {
                top1++;
            }
            if (rank >= 1 && rank <= 3) {
                top3++;
            }
            log.append(commit.caseId()).append(" expected ").append(expected)
                    .append(" rank=").append(rank < 0 ? "miss" : rank).append('\n');
            log.append("  abstraction: ").append(abstraction.replaceAll("\\s+", " ")).append('\n');
            for (PolicyHit hit : hits) {
                log.append(String.format("    %.3f %s %s%n", hit.score(), hit.chunk().id(),
                        hit.chunk().heading()));
            }
        }
        log.append("top1=").append(top1).append("/5 top3=").append(top3).append("/5\n");
        java.nio.file.Files.writeString(java.nio.file.Path.of("eval/abstraction-probe.txt"),
                log.toString(), java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("written to eval/abstraction-probe.txt");
    }

    private static String user(PlannedUnit unit) {
        StringBuilder text = new StringBuilder();
        text.append("改动的位置：").append(unit.unit().file()).append(" 的 ")
                .append(unit.unit().name()).append("\n\n");
        text.append("结构变化：\n");
        for (StructuralChange change : unit.changes()) {
            text.append("- ").append(change.describe()).append('\n');
        }
        text.append("\n改动前的代码：\n").append(unit.unit().textBefore()).append("\n\n");
        text.append("改动后的代码：\n").append(unit.unit().textAfter()).append('\n');
        return text.toString();
    }
}
