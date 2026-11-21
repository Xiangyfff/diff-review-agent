package com.codereview.agent.investigate;

import com.codereview.agent.investigate.tool.FindDefinitionTool;
import com.codereview.agent.investigate.tool.SearchTool;
import com.codereview.agent.triage.index.IndexBuilder;
import com.codereview.agent.triage.index.SymbolIndex;
import com.codereview.agent.triage.parse.SourceParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tools run against real files in a scratch directory under target, because the machine's system
 * temp directory is not writable here. What is asserted is what the model would actually receive.
 */
class ReviewToolsTest {

    private Path root;
    private RepoAccess repository;

    @BeforeEach
    void createRepository() throws IOException {
        root = Path.of("target", "test-investigate", Long.toHexString(System.nanoTime()));
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/Login.java"), """
                package a;

                class Login {
                    String handle(String user) {
                        if (user == null) {
                            return null;
                        }
                        return user.trim();
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("a/Caller.java"), """
                package a;

                class Caller {
                    String run(Login login) {
                        return login.handle(null);
                    }
                }
                """, StandardCharsets.UTF_8);
        SymbolIndex index = new IndexBuilder(new SourceParser()).build(root).index();
        repository = new RepoAccess(root, index);
    }

    @AfterEach
    void deleteRepository() throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void readsTheRequestedLinesWithTheirNumbers() {
        ToolOutcome outcome = new com.codereview.agent.investigate.tool.ReadLinesTool(repository)
                .run(new ToolCall("read_lines", Map.of("path", "a/Login.java", "start", "4", "end", "6")));

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.excerpts()).hasSize(1);
        assertThat(outcome.excerpts().get(0).text()).contains("5:").contains("user == null");
        assertThat(outcome.excerpts().get(0).startLine()).isEqualTo(4);
    }

    @Test
    void refusesToReadOutsideTheRepository() {
        ToolOutcome outcome = new com.codereview.agent.investigate.tool.ReadLinesTool(repository)
                .run(new ToolCall("read_lines", Map.of("path", "../../etc/passwd", "start", "1", "end", "3")));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.summary()).contains("no such file");
    }

    @Test
    void clampsARangeThatRunsPastTheEndOfTheFile() {
        ToolOutcome outcome = new com.codereview.agent.investigate.tool.ReadLinesTool(repository)
                .run(new ToolCall("read_lines", Map.of("path", "a/Login.java", "start", "7", "end", "99")));

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.excerpts().get(0).endLine()).isLessThanOrEqualTo(10);
    }

    @Test
    void findsWhereANameIsDeclared() {
        ToolOutcome outcome = new FindDefinitionTool(repository)
                .run(new ToolCall("find_definition", Map.of("symbol", "handle")));

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.summary()).contains("a/Login.java").contains("declared");
        assertThat(outcome.excerpts().get(0).text()).contains("handle");
    }

    @Test
    void listsTheFilesThatMentionAName() {
        ToolOutcome outcome = new com.codereview.agent.investigate.tool.FindReferencesTool(repository)
                .run(new ToolCall("find_references", Map.of("symbol", "handle")));

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.summary()).contains("2 file(s)");
        // The file list is only a fallback now; the useful answer is the matching lines themselves.
        assertThat(outcome.excerpts()).anySatisfy(excerpt ->
                assertThat(excerpt.file()).isEqualTo("a/Caller.java"));
        assertThat(outcome.excerpts()).anySatisfy(excerpt ->
                assertThat(excerpt.file()).isEqualTo("a/Login.java"));
    }

    @Test
    void searchesForALiteralString() {
        ToolOutcome outcome = new SearchTool(repository)
                .run(new ToolCall("search", Map.of("text", "login.handle")));

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.excerpts().get(0).text()).contains("a/Caller.java");
    }

    @Test
    void theOfflineModelProducesAConclusionThatPassesVerification() {
        InvestigationTask task = new InvestigationTask("u1", "a/Login.java", 5,
                "String handle(String user) {\n    return user.trim();\n}",
                "String handle(String user) {\n    return null;\n}",
                List.of("NULL_CHECK_REMOVED"), List.of("这个值是否可能为空？"), "");
        EvidenceStore empty = new EvidenceStore();

        InvestigationResult result = new InvestigationRunner(new MockInvestigationModel(),
                Toolbox.standard(repository), InvestigateConfig.defaults()).investigate(task);

        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.CONCLUDED);
        assertThat(result.findings()).hasSize(1);
        assertThat(empty.size()).isZero();
        Finding finding = result.findings().get(0);
        assertThat(finding.line()).isBetween(1, 10);
        assertThat(finding.evidenceIds()).containsExactly("ev-1");
    }
}
