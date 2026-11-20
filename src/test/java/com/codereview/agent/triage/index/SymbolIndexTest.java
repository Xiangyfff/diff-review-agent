package com.codereview.agent.triage.index;

import com.codereview.agent.triage.parse.SourceParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index is a hint, so what matters is that it counts what it can see, skips what it cannot
 * read, and never fails a run. Working directories live under target because the machine's
 * system temp directory is not writable in this environment.
 */
class SymbolIndexTest {

    private Path root;

    @BeforeEach
    void createRoot() throws IOException {
        root = Path.of("target", "test-index", Long.toHexString(System.nanoTime()));
        Files.createDirectories(root);
    }

    @AfterEach
    void deleteRoot() throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private IndexBuilder.Result build() throws IOException {
        return new IndexBuilder(new SourceParser()).build(root);
    }

    private void write(String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void countsFilesMentioningASymbolAndFindsPublicDeclarations() throws IOException {
        write("a/Service.java", """
                package a;
                public class Service {
                    public void handle() {
                    }
                }
                """);
        write("b/Caller.java", """
                package b;
                class Caller {
                    void run(Service service) {
                        service.handle();
                    }
                }
                """);

        SymbolFacts facts = build().index().factsFor("handle");

        assertThat(facts.mentioningFiles()).isEqualTo(2);
        assertThat(facts.otherFiles()).isEqualTo(1);
        assertThat(facts.declared()).isTrue();
        assertThat(facts.publiclyDeclared()).isTrue();
        assertThat(facts.describe()).contains("另外 1 个源文件").contains("public");
    }

    @Test
    void duplicateDeclarationsSayTheVisibilityCoversOnlyOneSite() throws IOException {
        write("a/Api.java", """
                package a;
                public class Api {
                    public void shared() {
                    }
                }
                """);
        write("b/ApiTest.java", """
                package b;
                class ApiTest {
                    void shared() {
                    }
                }
                """);

        SymbolFacts facts = build().index().factsFor("shared");

        assertThat(facts.declarationCount()).isEqualTo(2);
        assertThat(facts.describe()).contains("同名的声明有 2 处");
    }

    @Test
    void aSymbolUsedOnlyWhereItIsDeclaredHasNoOtherFiles() throws IOException {
        write("a/Quiet.java", """
                package a;
                class Quiet {
                    private void used() {
                    }
                }
                """);

        SymbolFacts facts = build().index().factsFor("used");

        assertThat(facts.otherFiles()).isZero();
        assertThat(facts.publiclyDeclared()).isFalse();
        assertThat(facts.describe()).contains("不是 public");
    }

    @Test
    void anUnknownSymbolProducesAnEmptyFactInsteadOfAGuess() throws IOException {
        write("a/Real.java", "class Real {}\n");

        SymbolFacts facts = build().index().factsFor("nowhere");

        assertThat(facts.isEmpty()).isTrue();
        assertThat(facts.mentioningFiles()).isZero();
    }

    @Test
    void skipsBuildOutputAndHiddenDirectories() throws IOException {
        write("a/Real.java", "class Real {}\n");
        write("target/Generated.java", "class Generated {}\n");
        write(".git/Hidden.java", "class Hidden {}\n");

        IndexBuilder.Result result = build();

        assertThat(result.filesScanned()).isEqualTo(1);
        assertThat(result.index().factsFor("Generated").declared()).isFalse();
    }

    @Test
    void countsUnparsableFilesInsteadOfFailing() throws IOException {
        write("a/Broken.java", "class Broken {\n    void broken( {\n");
        write("a/Fine.java", "class Fine {}\n");

        IndexBuilder.Result result = build();

        assertThat(result.filesScanned()).isEqualTo(2);
        assertThat(result.parseFailures()).isEqualTo(1);
        assertThat(result.index().factsFor("Fine").declared()).isTrue();
    }

    @Test
    void ignoresNonJavaFiles() throws IOException {
        write("README.md", "handle handle handle\n");

        assertThat(build().filesScanned()).isZero();
    }
}
