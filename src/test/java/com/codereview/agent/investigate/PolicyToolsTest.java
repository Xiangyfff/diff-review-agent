package com.codereview.agent.investigate;

import com.codereview.agent.investigate.tool.ReadPolicyTool;
import com.codereview.agent.investigate.tool.SearchPolicyTool;
import com.codereview.agent.triage.policy.PolicyIndex;
import com.codereview.agent.triage.policy.PolicyLibrary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyToolsTest {

    /** One axis for password wording, one for output wording: enough to tell two clauses apart. */
    private static final com.codereview.agent.triage.policy.Embedder AXES = text ->
            text.contains("口令") ? new float[]{1, 0} : new float[]{0, 1};

    @Test
    void aSearchReturnsClauseIdsWithTheirScores() throws IOException {
        PolicyIndex index = index();
        ToolOutcome outcome = new SearchPolicyTool(index).run(
                new ToolCall("search_policy", Map.of("query", "口令比较")));

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.summary()).contains("pol-security-1");
        assertThat(outcome.excerpts()).isNotEmpty();
        assertThat(outcome.excerpts().get(0).text()).contains("pol-security-1");
    }

    @Test
    void aReadReturnsTheWholeClauseIncludingTheExceptionAndTheExamples() throws IOException {
        PolicyIndex index = index();
        ToolOutcome outcome = new ReadPolicyTool(index).run(
                new ToolCall("read_policy", Map.of("id", "pol-security-1")));

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.excerpts().get(0).text()).contains("恒定时间");
        assertThat(outcome.excerpts().get(0).text()).contains("mandatory=true");
        // The example is part of the clause and the only place the document names its own fix;
        // leaving it out made an investigator's correct suggestion look invented.
        assertThat(outcome.excerpts().get(0).text()).contains("MessageDigest.isEqual");
    }

    @Test
    void anUnknownClauseIdIsAFailureNotAnEmptyAnswer() throws IOException {
        ToolOutcome outcome = new ReadPolicyTool(index()).run(
                new ToolCall("read_policy", Map.of("id", "pol-nope-1")));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.summary()).contains("no policy clause with id pol-nope-1");
    }

    @Test
    void aRepositoryWithoutPoliciesSaysSoInsteadOfInventingOne() {
        ToolOutcome outcome = new SearchPolicyTool(PolicyIndex.empty()).run(
                new ToolCall("search_policy", Map.of("query", "口令")));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.summary()).contains("no policy documents");
    }

    @Test
    void theToolboxOnlyCarriesPolicyToolsWhenThereArePolicies() throws IOException {
        RepoAccess repository = new RepoAccess(Path.of("target"), null);

        assertThat(Toolbox.standard(repository).find("search_policy")).isEmpty();
        Toolbox withPolicies = Toolbox.standard(repository, index());
        assertThat(withPolicies.find("search_policy")).isPresent();
        assertThat(withPolicies.find("read_policy")).isPresent();
        assertThat(withPolicies.all()).hasSize(9);
    }

    private static PolicyIndex index() throws IOException {
        Path root = Path.of("target", "test-policy", Long.toHexString(System.nanoTime()));
        Path docs = root.resolve("docs/policies");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("security.md"), """
                # 安全

                ## [强制] 口令比较

                口令必须使用恒定时间的比较方式，禁止使用 equals，因为逐字符比较会泄漏时间差。
                例外：仅用于测试的桩实现可以豁免。

                ```java
                MessageDigest.isEqual(storedBytes, submittedBytes);
                ```

                ## 非强制 日志

                日志使用结构化字段。
                """, StandardCharsets.UTF_8);
        try {
            return PolicyIndex.build(PolicyLibrary.load(docs), AXES, PolicyIndex.Scoring.SAMPLES);
        } finally {
            delete(root);
        }
    }

    private static void delete(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                path.toFile().setWritable(true);
                path.toFile().delete();
            });
        }
    }
}
