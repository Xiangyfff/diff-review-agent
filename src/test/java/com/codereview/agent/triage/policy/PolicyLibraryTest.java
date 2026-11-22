package com.codereview.agent.triage.policy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyLibraryTest {

    private static PolicyChunk chunk(List<PolicyChunk> chunks, String headingFragment) {
        return chunks.stream().filter(chunk -> chunk.heading().contains(headingFragment)).findFirst()
                .orElseThrow(() -> new AssertionError("no chunk for " + headingFragment));
    }

    @Test
    void splitsOnHeadingsAndKeepsTheHeadingText() {
        List<PolicyChunk> chunks = PolicyLibrary.split("docs/policies/security.md", """
                # 安全与凭据

                ## [强制] 认证失败的日志

                认证失败时只记录用户名与失败原因，不得记录口令。

                ## 口令比较

                口令必须使用恒定时间的比较方式。
                """);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).id()).isEqualTo("pol-docs-policies-security-1");
        assertThat(chunks.get(0).mandatory()).isTrue();
        assertThat(chunks.get(0).heading()).isEqualTo("认证失败的日志");
        assertThat(chunks.get(0).text()).contains("不得记录口令");
        assertThat(chunks.get(1).mandatory()).isFalse();
        assertThat(chunks.get(1).heading()).isEqualTo("口令比较");
    }

    @Test
    void aHeadingWithAlmostNoTextIsNotAChunk() {
        List<PolicyChunk> chunks = PolicyLibrary.split("a.md", """
                # 标题

                ## 空章节

                ## 有内容的章节

                这是一个可以对照的规则，长度足够。
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).heading()).isEqualTo("有内容的章节");
    }

    @Test
    void aDocumentWithoutHeadingsBecomesOneChunkNamedAfterTheFile() {
        List<PolicyChunk> chunks = PolicyLibrary.split("notes.md", "所有接口都必须返回 JSON 并带 request id。");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).heading()).isEqualTo("notes.md");
        assertThat(chunks.get(0).mandatory()).isFalse();
    }

    @Test
    void loadsEveryMarkdownFileInNameOrder() throws IOException {
        Path directory = Files.createTempDirectory(Path.of("target"), "policy-library-");
        Files.writeString(directory.resolve("b-security.md"), """
                # 安全

                ## [强制] 凭据

                日志里不得出现口令或令牌，这是强制条款。
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("a-api.md"), """
                # 接口

                ## 返回体

                所有响应必须是 JSON，并带上 request id。
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("ignore.txt"), "not a policy", StandardCharsets.UTF_8);

        PolicyLibrary library = PolicyLibrary.load(directory);

        assertThat(library.files()).isEqualTo(2);
        assertThat(library.chunks()).hasSize(2);
        assertThat(library.chunks().get(0).file()).isEqualTo("a-api.md");
        assertThat(library.chunks().get(1).file()).isEqualTo("b-security.md");
        Files.deleteIfExists(directory.resolve("a-api.md"));
        Files.deleteIfExists(directory.resolve("b-security.md"));
        Files.deleteIfExists(directory.resolve("ignore.txt"));
        Files.deleteIfExists(directory);
    }

    @Test
    void aMissingDirectoryIsAnEmptyLibraryRatherThanAnError() {
        PolicyLibrary library = PolicyLibrary.load(Path.of("target").resolve("no-such-policy-dir"));

        assertThat(library.isEmpty()).isTrue();
        assertThat(library.files()).isZero();
    }

    @Test
    void theCitationNamesTheFileAndTheHeadingNotJustTheId() {
        PolicyChunk chunk = new PolicyChunk("pol-security-1", "docs/policies/security.md",
                "认证失败的日志", "不得记录口令", true);

        assertThat(chunk.cite()).contains("pol-security-1").contains("docs/policies/security.md")
                .contains("认证失败的日志");
    }

    @Test
    void anExcerptIsCutToWidthWithAnEllipsis() {
        PolicyChunk chunk = new PolicyChunk("pol-x-1", "x.md", "h", "一".repeat(500), false);

        assertThat(chunk.excerpt(100)).hasSize(103).endsWith("...");
        assertThat(chunk.excerpt(2000)).hasSize(500);
    }
}
