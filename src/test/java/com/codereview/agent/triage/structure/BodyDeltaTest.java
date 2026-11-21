package com.codereview.agent.triage.structure;

import com.codereview.agent.triage.TestSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shape detection on the statement gaps. These are the rules the triage gate leans on, so
 * every case here is a shape a reviewer would otherwise have to spot by reading the diff.
 */
class BodyDeltaTest {

    private static final int LINE = 10;

    private static List<StructuralChange> compare(String before, String after) {
        List<StructuralChange> changes = new ArrayList<>();
        BodyDelta.compare(TestSource.member(before), TestSource.member(after), LINE, changes);
        return changes;
    }

    private static List<StructuralChange.Kind> kinds(List<StructuralChange> changes) {
        return changes.stream().map(StructuralChange::kind).toList();
    }

    @Test
    void aChangedComparisonOperatorIsItsOwnShape() {
        List<StructuralChange> changes = compare(
                "void m(int quantity) { boolean bulk = quantity > 10; use(bulk); }",
                "void m(int quantity) { boolean bulk = quantity >= 10; use(bulk); }");

        assertThat(kinds(changes)).containsExactly(StructuralChange.Kind.OPERATOR_CHANGED);
        assertThat(changes.get(0).confidence()).isEqualTo(StructuralChange.Confidence.EXACT);
        assertThat(changes.get(0).detail()).contains(">=").contains(">");
    }

    @Test
    void aChangedNumberIsNotAnOperatorChange() {
        List<StructuralChange> changes = compare(
                "void m(int quantity) { boolean bulk = quantity > 10; use(bulk); }",
                "void m(int quantity) { boolean bulk = quantity > 20; use(bulk); }");

        assertThat(kinds(changes)).doesNotContain(StructuralChange.Kind.OPERATOR_CHANGED);
    }

    @Test
    void removingANullGuardIsReportedAsItsOwnShape() {
        List<StructuralChange> changes = compare(
                "void m(String x) { if (x == null) { return; } use(x); }",
                "void m(String x) { use(x); }");

        assertThat(kinds(changes)).containsExactly(StructuralChange.Kind.NULL_CHECK_REMOVED);
        assertThat(changes.get(0).confidence()).isEqualTo(StructuralChange.Confidence.EXACT);
        assertThat(changes.get(0).line()).isEqualTo(LINE);
    }

    @Test
    void negatingAConditionIsReportedAsAnInversion() {
        List<StructuralChange> changes = compare(
                "void m(boolean a) { if (a) { one(); } else { two(); } }",
                "void m(boolean a) { if (!a) { one(); } else { two(); } }");

        assertThat(kinds(changes)).containsExactly(StructuralChange.Kind.CONDITION_INVERTED);
    }

    @Test
    void swappingTheBranchesIsAnEquivalentRewriteAndIsNotAnInversion() {
        List<StructuralChange> changes = compare(
                "void m(boolean a) { if (a) { one(); } else { two(); } }",
                "void m(boolean a) { if (!a) { two(); } else { one(); } }");

        assertThat(kinds(changes)).doesNotContain(StructuralChange.Kind.CONDITION_INVERTED);
    }

    @Test
    void anEmptyCatchBlockIsASwallowedException() {
        List<StructuralChange> changes = compare(
                "void m() { risky(); }",
                "void m() { try { risky(); } catch (Exception failure) { } }");

        assertThat(changes)
                .filteredOn(change -> change.kind() == StructuralChange.Kind.SWALLOWED_EXCEPTION)
                .singleElement()
                .satisfies(change -> assertThat(change.confidence()).isEqualTo(StructuralChange.Confidence.EXACT));
    }

    @Test
    void aCatchBlockThatOnlyLogsIsStillReportedButNotAsCertain() {
        List<StructuralChange> changes = compare(
                "void m() { risky(); }",
                "void m() { try { risky(); } catch (Exception failure) { log.warn(\"ignored\", failure); } }");

        assertThat(changes)
                .filteredOn(change -> change.kind() == StructuralChange.Kind.SWALLOWED_EXCEPTION)
                .singleElement()
                .satisfies(change -> assertThat(change.confidence()).isEqualTo(StructuralChange.Confidence.HEURISTIC));
    }

    @Test
    void anOrdinaryAddedStatementIsReportedAsSuch() {
        List<StructuralChange> changes = compare("void m() { a(); }", "void m() { a(); b(); }");

        assertThat(kinds(changes)).containsExactly(StructuralChange.Kind.STATEMENTS_ADDED);
    }

    @Test
    void removingATopLevelReturnIsAnEarlyExitRemoval() {
        List<StructuralChange> changes = compare(
                "void m() { return; use(); }",
                "void m() { use(); }");

        assertThat(kinds(changes)).containsExactly(StructuralChange.Kind.EARLY_EXIT_REMOVED);
    }

    @Test
    void identicalBodiesProduceNothing() {
        assertThat(compare("void m() { a(); b(); }", "void m() { a(); b(); }")).isEmpty();
    }

    @Test
    void aReturnSwappedForAnotherReturnIsNotALostExit() {
        List<StructuralChange> changes = compare(
                """
                int size(List<String> items) {
                    return items.size();
                }
                """,
                """
                int size(List<String> items) {
                    return items.size() * 2;
                }
                """);

        assertThat(kinds(changes)).doesNotContain(StructuralChange.Kind.EARLY_EXIT_REMOVED);
    }
}
