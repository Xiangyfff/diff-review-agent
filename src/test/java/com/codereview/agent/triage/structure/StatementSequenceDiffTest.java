package com.codereview.agent.triage.structure;

import com.codereview.agent.triage.TestSource;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StatementSequenceDiffTest {

    private static List<Statement> statements(String body) {
        MethodDeclaration method = (MethodDeclaration) TestSource.member("void m() { " + body + " }");
        return method.getBody().orElseThrow().getStatements();
    }

    private static List<StatementSequenceDiff.Block> diff(String before, String after) {
        return StatementSequenceDiff.diff(statements(before), statements(after), Set.of());
    }

    @Test
    void identicalSequencesProduceNoGaps() {
        assertThat(diff("a(); b(); c();", "a(); b(); c();")).isEmpty();
    }

    @Test
    void insertionBecomesOneAddedBlock() {
        List<StatementSequenceDiff.Block> blocks = diff("a(); c();", "a(); b(); c();");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).removed()).isEmpty();
        assertThat(blocks.get(0).added()).hasSize(1);
    }

    @Test
    void deletionBecomesOneRemovedBlock() {
        List<StatementSequenceDiff.Block> blocks = diff("a(); b(); c();", "a(); c();");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).removed()).hasSize(1);
        assertThat(blocks.get(0).added()).isEmpty();
    }

    @Test
    void replacementKeepsBothSidesInOneBlock() {
        List<StatementSequenceDiff.Block> blocks = diff("a(); b();", "a(); c();");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).removed()).hasSize(1);
        assertThat(blocks.get(0).added()).hasSize(1);
        assertThat(blocks.get(0).delta()).isEqualTo(2);
    }

    @Test
    void untouchedStatementsBetweenTwoChangesStayOut() {
        List<StatementSequenceDiff.Block> blocks = diff("a(); b(); c();", "a1(); b(); c1();");

        assertThat(blocks).hasSize(2);
        assertThat(blocks).allSatisfy(block -> assertThat(block.delta()).isEqualTo(2));
    }

    @Test
    void emptyBodiesProduceNoGaps() {
        assertThat(diff("", "")).isEmpty();
    }
}
