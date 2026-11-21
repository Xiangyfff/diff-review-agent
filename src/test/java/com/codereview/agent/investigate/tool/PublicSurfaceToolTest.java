package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;
import com.codereview.agent.triage.index.SymbolIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of this tool is to keep a public contract change from being dismissed just because no
 * caller is visible in the checkout, so the fixture covers the three visibility levels separately.
 */
class PublicSurfaceToolTest {

    private Path root;
    private PublicSurfaceTool tool;

    @BeforeEach
    void createProject() throws IOException {
        root = Files.createTempDirectory(Path.of("target"), "surface-");
        write("src/main/java/x/PublicApi.java", """
                package x;

                public class PublicApi {
                    public void reachable(String value) {
                        System.out.println(value);
                    }

                    void packageOnly(String value) {
                        System.out.println(value);
                    }

                    private void hidden(String value) {
                        System.out.println(value);
                    }
                }
                """);
        write("src/main/java/x/InternalApi.java", """
                package x;

                class InternalApi {
                    public void reachable(String value) {
                        System.out.println(value);
                    }
                }
                """);
        tool = new PublicSurfaceTool(new RepoAccess(root, SymbolIndex.empty()));
    }

    @AfterEach
    void deleteProject() throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void aPublicMethodOnAPublicTypeCanBeCalledFromOutside() {
        ToolOutcome outcome = run("src/main/java/x/PublicApi.java", "reachable", 4);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.summary()).contains("public")
                .contains("reachable from outside the repository");
    }

    @Test
    void aPackagePrivateMethodCannot() {
        ToolOutcome outcome = run("src/main/java/x/PublicApi.java", "packageOnly", 8);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.summary()).contains("package-private");
        assertThat(outcome.summary()).doesNotContain("reachable from outside");
    }

    @Test
    void aPrivateMethodCannot() {
        ToolOutcome outcome = run("src/main/java/x/PublicApi.java", "hidden", 12);

        assertThat(outcome.summary()).contains("private")
                .doesNotContain("reachable from outside");
    }

    @Test
    void aPublicMethodOnAnInternalTypeIsNotTheSameThing() {
        ToolOutcome outcome = run("src/main/java/x/InternalApi.java", "reachable", 4);

        assertThat(outcome.summary()).contains("public");
        assertThat(outcome.summary()).doesNotContain("reachable from outside the repository");
    }

    @Test
    void anUnknownSymbolIsReportedNotGuessed() {
        ToolOutcome outcome = run("src/main/java/x/PublicApi.java", "missing", 4);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.summary()).contains("no declaration named missing");
    }

    private ToolOutcome run(String file, String symbol, int line) {
        return tool.run(new ToolCall("public_surface",
                Map.of("file", file, "symbol", symbol, "line", Integer.toString(line))));
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
