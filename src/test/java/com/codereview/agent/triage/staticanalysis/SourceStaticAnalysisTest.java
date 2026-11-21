package com.codereview.agent.triage.staticanalysis;

import com.codereview.agent.triage.diff.FileChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceStaticAnalysisTest {

    private final SourceStaticAnalysis analysis = new SourceStaticAnalysis();

    @Test
    void flagsAStringComparedWithEquals() {
        String text = """
                class A {
                    boolean same(String left, String right) {
                        return left == "yes";
                    }
                }
                """;
        assertThat(analysis.analyse("src/main/java/A.java", text))
                .anySatisfy(warning -> {
                    assertThat(warning.kind()).isEqualTo("STRING_EQUALITY");
                    assertThat(warning.line()).isEqualTo(3);
                });
    }

    @Test
    void doesNotFlagAComparisonWithNull() {
        String text = """
                class A {
                    boolean empty(String value) {
                        return value == null;
                    }
                }
                """;
        assertThat(analysis.analyse("src/main/java/A.java", text))
                .noneMatch(warning -> warning.kind().equals("STRING_EQUALITY"));
    }

    @Test
    void flagsCatchingThrowable() {
        String text = """
                class A {
                    void run() {
                        try {
                            work();
                        } catch (Throwable failure) {
                            return;
                        }
                    }
                    void work() {
                    }
                }
                """;
        assertThat(analysis.analyse("src/main/java/A.java", text))
                .anyMatch(warning -> warning.kind().equals("CATCH_THROWABLE"));
    }

    @Test
    void flagsAStandardStreamWriteInProductionCode() {
        String text = """
                class A {
                    void run(String value) {
                        System.out.println(value);
                    }
                }
                """;
        assertThat(analysis.analyse("src/main/java/A.java", text))
                .anyMatch(warning -> warning.kind().equals("DEBUG_PRINT"));
    }

    @Test
    void keepsOnlyWarningsOnAddedLines() {
        String base = """
                class A {
                    boolean first(String left, String right) {
                        return left.equals(right);
                    }
                    boolean second(String left, String right) {
                        return left.equals(right);
                    }
                }
                """;
        String head = """
                class A {
                    boolean first(String left, String right) {
                        return left.equals(right);
                    }
                    boolean second(String left, String right) {
                        return left == "yes";
                    }
                }
                """;
        // Lines 1-3 are untouched; the change added line 6.
        FileChange change = new FileChange("src/main/java/A.java", "src/main/java/A.java",
                FileChange.ChangeType.MODIFIED,
                List.of(FileChange.EditRange.fromZeroBasedHalfOpen(5, 5, 5, 6)), base, head);

        assertThat(analysis.onAddedLines(change))
                .extracting(StaticWarning::line)
                .containsExactly(6);
    }

    @Test
    void saysNothingWhenTheFileDoesNotParse() {
        assertThat(analysis.analyse("src/main/java/A.java", "class {")).isEmpty();
    }
}
