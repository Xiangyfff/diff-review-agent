package com.codereview.agent.triage.structure;

import com.codereview.agent.triage.TestSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The declaration-level half of shape detection: everything outside the method body. */
class DeclarationDeltaTest {

    private static final int LINE = 7;

    private static List<StructuralChange> compare(String before, String after) {
        List<StructuralChange> changes = new ArrayList<>();
        DeclarationDelta.compare(TestSource.member(before), TestSource.member(after), LINE, changes);
        return changes;
    }

    private static List<StructuralChange.Kind> kinds(List<StructuralChange> changes) {
        return changes.stream().map(StructuralChange::kind).toList();
    }

    @Test
    void narrowingVisibilityIsAContractChange() {
        assertThat(kinds(compare("public void m() {}", "private void m() {}")))
                .containsExactly(StructuralChange.Kind.VISIBILITY_CHANGED);
    }

    @Test
    void addingAParameterChangesTheSignature() {
        assertThat(kinds(compare("void m(int a) {}", "void m(int a, int b) {}")))
                .containsExactly(StructuralChange.Kind.SIGNATURE_CHANGED);
    }

    @Test
    void changingTheReturnTypeChangesTheSignature() {
        assertThat(kinds(compare("int m() { return 1; }", "long m() { return 1L; }")))
                .containsExactly(StructuralChange.Kind.SIGNATURE_CHANGED);
    }

    @Test
    void renamingAParameterIsNotASignatureChange() {
        assertThat(compare("void m(int a) {}", "void m(int b) {}")).isEmpty();
    }

    @Test
    void addingACheckedExceptionChangesTheThrowsClause() {
        assertThat(kinds(compare("void m() {}", "void m() throws java.io.IOException {}")))
                .containsExactly(StructuralChange.Kind.THROWS_CHANGED);
    }

    @Test
    void droppingAnAnnotationIsReported() {
        assertThat(kinds(compare("@Deprecated void m() {}", "void m() {}")))
                .containsExactly(StructuralChange.Kind.ANNOTATION_REMOVED);
    }

    @Test
    void addingAModifierIsReported() {
        assertThat(kinds(compare("void m() {}", "static void m() {}")))
                .containsExactly(StructuralChange.Kind.MODIFIER_CHANGED);
    }

    @Test
    void aMemberThatChangedKindGetsOneChangeRatherThanFive() {
        List<StructuralChange> changes = compare("void m() {}", "int m;");

        assertThat(kinds(changes)).containsExactly(StructuralChange.Kind.SIGNATURE_CHANGED);
        assertThat(changes.get(0).detail()).contains("declaration kind changed");
    }
}
