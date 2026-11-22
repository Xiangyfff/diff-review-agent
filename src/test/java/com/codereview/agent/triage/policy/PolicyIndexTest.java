package com.codereview.agent.triage.policy;

import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.unit.AnalysisUnit;
import com.codereview.agent.triage.unit.SourceUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyIndexTest {

    /**
     * A stand-in embedder with two hand-made axes: credentials and response shape. It makes the
     * retrieval logic testable without a model, which is the only reason retrieval sits behind
     * an interface.
     */
    private static final Embedder TWO_AXES = text -> {
        double credentials = text.contains("口令") || text.contains("凭据") || text.contains("认证") ? 1 : 0;
        double shapes = text.contains("JSON") || text.contains("接口") || text.contains("响应") ? 1 : 0;
        return new float[]{(float) (credentials == 0 ? 0.01 : credentials), (float) (shapes == 0 ? 0.01 : shapes)};
    };

    private static PolicyLibrary library() {
        return PolicyLibrary.of(PolicyLibrary.split("docs/policies/security.md", """
                # 安全

                ## [强制] 认证失败的日志

                认证失败时不得记录口令，也不得记录令牌，这是强制条款。

                ## 响应体

                所有响应必须是 JSON，并带上 request id。
                """));
    }

    @Test
    void returnsTheChunkWhoseAxisMatchesTheQuery() {
        PolicyIndex index = PolicyIndex.build(library(), TWO_AXES);

        List<PolicyHit> hits = index.search("日志里打印了口令，需要排查", 3, 0.5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunk().heading()).isEqualTo("认证失败的日志");
        assertThat(hits.get(0).chunk().mandatory()).isTrue();
        assertThat(hits.get(0).score()).isGreaterThan(0.9);
    }

    @Test
    void aQueryBelowTheCutReturnsNothingRatherThanTheBestOfABadLot() {
        PolicyIndex index = PolicyIndex.build(library(), TWO_AXES);

        assertThat(index.search("把循环改成流式写法", 3, 0.9)).isEmpty();
    }

    @Test
    void keepsOnlyTheTopKOfManyMatches() {
        PolicyIndex index = PolicyIndex.build(library(), TWO_AXES);

        assertThat(index.search("口令与 JSON 响应", 1, 0.5)).hasSize(1);
    }

    @Test
    void anEmptyIndexAnswersWithoutCallingTheEmbedder() {
        Embedder exploding = text -> {
            throw new AssertionError("an empty index must not embed");
        };

        assertThat(PolicyIndex.empty().search("anything", 3, 0.5)).isEmpty();
        assertThat(PolicyIndex.build(PolicyLibrary.empty(), exploding).isEmpty()).isTrue();
    }

    @Test
    void aChunkThatCannotBeEmbeddedIsDroppedAndCounted() {
        Embedder halfBroken = text -> {
            if (text.contains("JSON")) {
                throw new IllegalStateException("endpoint down");
            }
            return new float[]{1, 0};
        };

        PolicyIndex index = PolicyIndex.build(library(), halfBroken);

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.embedFailures()).isEqualTo(1);
        assertThat(index.search("口令", 3, 0.5)).hasSize(1);
    }

    @Test
    void mandatoryHitsAreTheOnesThatGateTheTier() {
        PolicyIndex index = PolicyIndex.build(library(), TWO_AXES);

        List<PolicyHit> hits = index.search("口令与 JSON 响应", 3, 0.5);

        assertThat(PolicyIndex.mandatory(hits)).extracting(hit -> hit.chunk().heading())
                .containsExactly("认证失败的日志");
    }

    @Test
    void theQueryIsBuiltFromTheChangeNotFromTheWholeDiff() {
        AnalysisUnit unit = new AnalysisUnit("src/main/java/Auth.java", "Auth.login",
                new SourceUnit(SourceUnit.Kind.METHOD, "Auth.login", 10, 20, "void login() {}"),
                new SourceUnit(SourceUnit.Kind.METHOD, "Auth.login", 10, 22, "void login() { log(p); }"),
                List.of(), 1, 0);

        assertThat(PolicyQuery.of(unit, List.of())).contains("src/main/java/Auth.java").contains("Auth.login");
    }

    @Test
    void theQueryDescribesTheChangeInTheLanguageTheClausesAreWrittenIn() {
        AnalysisUnit unit = new AnalysisUnit("src/main/java/Auth.java", "METHOD#Auth.login",
                new SourceUnit(SourceUnit.Kind.METHOD, "Auth.login", 10, 14, "public void login() {}"),
                new SourceUnit(SourceUnit.Kind.METHOD, "Auth.login", 10, 14, "public void login() {}"),
                java.util.List.of(), 0, 0);
        StructuralChange change = StructuralChange.exact(StructuralChange.Kind.NULL_CHECK_REMOVED,
                12, "null guard on `user` is gone", "if (user == null) { return; }", "");

        String query = PolicyQuery.of(unit, java.util.List.of(change));

        assertThat(query).contains("删掉了空值检查").contains("Auth.login")
                .contains("改动后的代码");
    }
}
