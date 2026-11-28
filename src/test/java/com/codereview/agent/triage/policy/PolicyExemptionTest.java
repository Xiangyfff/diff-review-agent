package com.codereview.agent.triage.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyExemptionTest {

    private static PolicyChunk allowance(String... rules) {
        return new PolicyChunk("pol-api-1", "docs/policies/api.md", "公开回调允许空参数",
                "公开 API 的回调参数允许传 null。豁免规则：NULL-CHECK-REMOVED。适用范围：src/main/java/**。",
                false, false, true, List.of(rules), List.of("src/main/java/**"),
                List.of("handler.accept(null);"));
    }

    @Test
    void anAllowanceInScopeOnAStrongHitIsFound() {
        PolicyExemption exemption = PolicyExemption.find("src/main/java/com/example/Api.java",
                List.of(new PolicyHit(allowance("NULL-CHECK-REMOVED"), 0.81)), 0.55);

        assertThat(exemption).isNotNull();
        assertThat(exemption.clauseId()).isEqualTo("pol-api-1");
        assertThat(exemption.exempts("NULL-CHECK-REMOVED")).isTrue();
        assertThat(exemption.exempts("OPERATOR-CHANGED")).isFalse();
    }

    @Test
    void aPathOutsideTheDeclaredScopeIsNotExempted() {
        PolicyExemption exemption = PolicyExemption.find("src/test/java/com/example/ApiTest.java",
                List.of(new PolicyHit(allowance("NULL-CHECK-REMOVED"), 0.81)), 0.55);

        assertThat(exemption).isNull();
    }

    @Test
    void aWeakHitIsNotEnoughToExempt() {
        PolicyExemption exemption = PolicyExemption.find("src/main/java/com/example/Api.java",
                List.of(new PolicyHit(allowance("NULL-CHECK-REMOVED"), 0.40)), 0.55);

        assertThat(exemption).isNull();
    }

    @Test
    void anAllowanceWithoutRulesOrExamplesCannotExempt() {
        PolicyChunk noRules = new PolicyChunk("pol-x-1", "x.md", "允许", "允许这么做，因为历史原因。",
                false, false, true, List.of(), List.of("src/**"), List.of("call();"));
        PolicyChunk noExamples = new PolicyChunk("pol-x-2", "x.md", "允许", "允许这么做，因为历史原因。",
                false, false, true, List.of("NULL-CHECK-REMOVED"), List.of("src/**"), List.of());

        assertThat(PolicyExemption.find("src/A.java",
                List.of(new PolicyHit(noRules, 0.9)), 0.55)).isNull();
        assertThat(PolicyExemption.find("src/A.java",
                List.of(new PolicyHit(noExamples, 0.9)), 0.55)).isNull();
    }

    @Test
    void aSingleStarStaysInsideOneDirectoryButADoubleStarCrossesIt() {
        assertThat(PolicyExemption.matches("src/*/Api.java", "src/main/Api.java")).isTrue();
        assertThat(PolicyExemption.matches("src/*/Api.java", "src/main/java/Api.java")).isFalse();
        assertThat(PolicyExemption.matches("src/**/Api.java", "src/main/java/Api.java")).isTrue();
    }

    @Test
    void aScopeWithoutWildcardsIsAPrefix() {
        assertThat(PolicyExemption.matches("src/main/java", "src/main/java/com/A.java")).isTrue();
        assertThat(PolicyExemption.matches("src/main/java", "src/main/kotlin/A.kt")).isFalse();
    }
}
