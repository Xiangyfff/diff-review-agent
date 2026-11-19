package com.codereview.agent.triage.unit;

import com.codereview.agent.triage.TestSource;
import com.codereview.agent.triage.diff.FileChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hunks to units. The contract under test: one method is one unit however many hunks land
 * in it, and a change that belongs to no member still gets a unit of its own.
 */
class UnitMapperTest {

    private final UnitMapper mapper = new UnitMapper();

    private static FileChange modified(String before, String after, FileChange.EditRange... edits) {
        return new FileChange("A.java", "A.java", FileChange.ChangeType.MODIFIED, List.of(edits), before, after);
    }

    @Test
    void aHunkInsideAMethodBecomesThatMethod() {
        String before = """
                class Sample {
                    void target() {
                        if (x != null) {
                            use(x);
                        }
                    }
                }
                """;
        String after = """
                class Sample {
                    void target() {
                        use(x);
                    }
                }
                """;

        List<AnalysisUnit> units = mapper.map(
                modified(before, after, new FileChange.EditRange(3, 5, 3, 3)),
                TestSource.file(before), TestSource.file(after));

        assertThat(units).hasSize(1);
        AnalysisUnit unit = units.get(0);
        assertThat(unit.kind()).isEqualTo(SourceUnit.Kind.METHOD);
        assertThat(unit.name()).contains("target");
        assertThat(unit.edits()).hasSize(1);
        assertThat(unit.addedLines()).isEqualTo(1);
        assertThat(unit.removedLines()).isEqualTo(3);
        assertThat(unit.textAfter()).contains("use(x)");
    }

    @Test
    void twoHunksInOneMethodCollapseIntoOneUnit() {
        String before = """
                class Sample {
                    void target() {
                        a();
                        b();
                        c();
                    }
                }
                """;
        String after = """
                class Sample {
                    void target() {
                        a2();
                        b();
                        c2();
                    }
                }
                """;

        List<AnalysisUnit> units = mapper.map(
                modified(before, after,
                        new FileChange.EditRange(3, 3, 3, 3),
                        new FileChange.EditRange(5, 5, 5, 5)),
                TestSource.file(before), TestSource.file(after));

        assertThat(units).hasSize(1);
        assertThat(units.get(0).edits()).hasSize(2);
        assertThat(units.get(0).removedLines()).isEqualTo(2);
    }

    @Test
    void aChangeOutsideEveryMemberStillGetsAUnit() {
        String before = """
                import java.util.List;

                class Sample {
                    void target() {
                        a();
                    }
                }
                """;
        String after = """
                import java.util.Set;

                class Sample {
                    void target() {
                        a();
                    }
                }
                """;

        List<AnalysisUnit> units = mapper.map(
                modified(before, after, new FileChange.EditRange(1, 1, 1, 1)),
                TestSource.file(before), TestSource.file(after));

        assertThat(units).hasSize(1);
        assertThat(units.get(0).kind()).isEqualTo(SourceUnit.Kind.TYPE_LEVEL);
        assertThat(units.get(0).textBefore()).isEqualTo("import java.util.List;");
        assertThat(units.get(0).textAfter()).isEqualTo("import java.util.Set;");
    }

    @Test
    void anAddedFileYieldsOneUnitPerMember() {
        String after = """
                class Sample {
                    int count;
                    void one() { }
                    void two() { }
                }
                """;
        FileChange added = new FileChange(null, "A.java", FileChange.ChangeType.ADDED,
                List.of(new FileChange.EditRange(1, 0, 1, 5)), null, after);

        List<AnalysisUnit> units = mapper.map(added, null, TestSource.file(after));

        assertThat(units).hasSize(3);
        assertThat(units).allSatisfy(unit -> assertThat(unit.isAdded()).isTrue());
        assertThat(units).extracting(AnalysisUnit::kind)
                .contains(SourceUnit.Kind.METHOD, SourceUnit.Kind.FIELD);
    }
}
