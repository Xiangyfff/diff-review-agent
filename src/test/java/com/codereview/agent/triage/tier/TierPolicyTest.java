package com.codereview.agent.triage.tier;

import com.codereview.agent.triage.diff.FileChange;
import com.codereview.agent.triage.rules.RiskCategory;
import com.codereview.agent.triage.rules.RuleCatalog;
import com.codereview.agent.triage.rules.RuleContext;
import com.codereview.agent.triage.rules.RuleHit;
import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.unit.AnalysisUnit;
import com.codereview.agent.triage.unit.SourceUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TierPolicyTest {

    private final TierPolicy policy = new TierPolicy();
    private final RuleCatalog rules = RuleCatalog.defaults();

    @Test
    void aSmallChangeWithNoRecognisedShapeIsWavedThrough() {
        AnalysisUnit unit = unit(1, 1, """
                void save(Order order) {
                    log.info("saving");
                }
                """, """
                void save(Order order) {
                    log.info("saving order");
                }
                """);
        List<StructuralChange> changes = List.of(
                StructuralChange.heuristic(StructuralChange.Kind.STATEMENTS_DELETED, 10,
                        "1 statement(s) removed with no recognised shape", "log.info(\"saving\");", ""),
                StructuralChange.heuristic(StructuralChange.Kind.STATEMENTS_ADDED, 10,
                        "1 statement(s) added with no recognised shape", "",
                        "log.info(\"saving order\");"));

        TierPolicy.Decision decision = decide(unit, changes);

        assertThat(decision.tier()).isEqualTo(Tier.SKIP);
        assertThat(TierPolicy.isCheapGate(decision.reason())).isTrue();
    }

    @Test
    void aLargeChangeWithNoRecognisedShapeStillGoesToTheModel() {
        AnalysisUnit unit = unit(6, 0, "void a() {\n}\n", """
                void a() {
                    one();
                    two();
                    three();
                    four();
                    five();
                    six();
                }
                """);
        List<StructuralChange> changes = List.of(StructuralChange.heuristic(
                StructuralChange.Kind.STATEMENTS_ADDED, 10, "6 statement(s) added with no recognised shape",
                "", "one();\ntwo();"));

        TierPolicy.Decision decision = decide(unit, changes);

        assertThat(decision.tier()).isEqualTo(Tier.JUDGE);
        assertThat(TierPolicy.isCheapGate(decision.reason())).isFalse();
    }

    @Test
    void anAdvisoryRuleBeatsTheGate() {
        AnalysisUnit unit = unit(1, 1, """
                boolean ready(Order order) {
                    return order.isPaid();
                }
                """, """
                boolean ready(Order order) {
                    return !order.isPaid();
                }
                """);
        List<StructuralChange> changes = List.of(StructuralChange.exact(
                StructuralChange.Kind.CONDITION_INVERTED, 10, "the condition was inverted",
                "order.isPaid()", "!order.isPaid()"));

        TierPolicy.Decision decision = decide(unit, changes);

        assertThat(decision.tier()).isEqualTo(Tier.JUDGE);
        assertThat(TierPolicy.isCheapGate(decision.reason())).isFalse();
        assertThat(decision.category()).isEqualTo(RiskCategory.LOGIC);
    }

    @Test
    void aMandatoryRuleStillForcesInvestigation() {
        AnalysisUnit unit = unit(1, 1, """
                void apply(Order order) {
                    if (order == null) {
                        return;
                    }
                    order.apply();
                }
                """, """
                void apply(Order order) {
                    order.apply();
                }
                """);
        List<StructuralChange> changes = List.of(StructuralChange.exact(
                StructuralChange.Kind.NULL_CHECK_REMOVED, 10, "removed `if (order == null)`",
                "if (order == null) { return; }", ""));

        TierPolicy.Decision decision = decide(unit, changes);

        assertThat(decision.tier()).isEqualTo(Tier.MUST_INVESTIGATE);
        assertThat(decision.investigate()).isTrue();
    }

    private TierPolicy.Decision decide(AnalysisUnit unit, List<StructuralChange> changes) {
        RuleContext context = new RuleContext(unit, changes);
        List<RuleHit> hits = rules.evaluate(context);
        return policy.decide(context, hits);
    }

    private static AnalysisUnit unit(int added, int removed, String before, String after) {
        SourceUnit beforeUnit = new SourceUnit(SourceUnit.Kind.METHOD, "Sample.save()", 10, 14, before);
        SourceUnit afterUnit = new SourceUnit(SourceUnit.Kind.METHOD, "Sample.save()", 10, 14, after);
        return new AnalysisUnit("src/main/java/Sample.java", "METHOD#Sample.save()",
                beforeUnit, afterUnit, List.of(new FileChange.EditRange(10, 14, 10, 14)),
                added, removed);
    }
}
