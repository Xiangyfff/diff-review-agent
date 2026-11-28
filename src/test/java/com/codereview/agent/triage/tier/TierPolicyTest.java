package com.codereview.agent.triage.tier;

import com.codereview.agent.triage.diff.FileChange;
import com.codereview.agent.triage.policy.PolicyChunk;
import com.codereview.agent.triage.policy.PolicyExemption;
import com.codereview.agent.triage.policy.PolicyHit;
import com.codereview.agent.triage.rules.RiskCategory;
import com.codereview.agent.triage.rules.Rule;
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
    void aRecognisedShapeBeatsTheGate() {
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

        assertThat(decision.tier()).isEqualTo(Tier.MUST_INVESTIGATE);
        assertThat(TierPolicy.isCheapGate(decision.reason())).isFalse();
        assertThat(decision.category()).isEqualTo(RiskCategory.LOGIC);
    }

    @Test
    void aRemovedEarlyExitIsInvestigatedWithoutAskingTheModel() {
        AnalysisUnit unit = unit(1, 1, """
                String name(Order order) {
                    if (order == null) {
                        return "unknown";
                    }
                    return order.name();
                }
                """, """
                String name(Order order) {
                    return order.name();
                }
                """);
        List<StructuralChange> changes = List.of(StructuralChange.exact(
                StructuralChange.Kind.EARLY_EXIT_REMOVED, 10, "a return was removed",
                "return \"unknown\";", ""));

        TierPolicy.Decision decision = decide(unit, changes);

        assertThat(decision.tier()).isEqualTo(Tier.MUST_INVESTIGATE);
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

    @Test
    void aMandatoryPolicySectionIsEnoughToInvestigate() {
        AnalysisUnit unit = unit(1, 0, "void save() {\n}\n", "void save() {\n    log.info(\"x\");\n}\n");
        PolicyHit hit = new PolicyHit(new PolicyChunk("pol-security-1", "docs/policies/security.md",
                "认证失败的日志", "认证失败时不得记录口令，也不得记录令牌。", true), 0.82);
        List<StructuralChange> changes = List.of(StructuralChange.heuristic(
                StructuralChange.Kind.STATEMENTS_ADDED, 12, "1 statement(s) added", "", "log.info(\"x\");"));
        RuleContext context = new RuleContext(unit, changes, List.of(), List.of(hit));

        TierPolicy.Decision decision = policy.decide(context, rules.evaluate(context));

        assertThat(decision.tier()).isEqualTo(Tier.MUST_INVESTIGATE);
        assertThat(decision.category()).isEqualTo(RiskCategory.CONSISTENCY);
    }

    @Test
    void aPolicySectionThatIsNotMandatoryDoesNotGate() {
        AnalysisUnit unit = unit(1, 0, "void save() {\n}\n", "void save() {\n    log.info(\"x\");\n}\n");
        PolicyHit hit = new PolicyHit(new PolicyChunk("pol-api-1", "docs/policies/api.md",
                "响应体", "所有响应必须是 JSON 并带上 request id。", false), 0.82);
        List<StructuralChange> changes = List.of(StructuralChange.heuristic(
                StructuralChange.Kind.STATEMENTS_ADDED, 12, "1 statement(s) added", "", "log.info(\"x\");"));
        RuleContext context = new RuleContext(unit, changes, List.of(), List.of(hit));

        TierPolicy.Decision decision = policy.decide(context, rules.evaluate(context));

        assertThat(decision.tier()).isNotEqualTo(Tier.MUST_INVESTIGATE);
    }

    @Test
    void anAllowanceClauseSkipsAUnitWhenNoGatingRuleRemains() {
        AnalysisUnit unit = unit(1, 0, "void save() {\n}\n", "void save() {\n    log.info(\"x\");\n}\n");
        List<StructuralChange> changes = List.of(StructuralChange.heuristic(
                StructuralChange.Kind.STATEMENTS_ADDED, 12, "1 statement(s) added", "", "log.info(\"x\");"));
        RuleContext context = new RuleContext(unit, changes);
        PolicyExemption exemption = new PolicyExemption("pol-api-1", 0.83,
                List.of("NULL-CHECK-REMOVED"));

        TierPolicy.Decision decision = policy.decide(context, rules.evaluate(context), exemption);

        assertThat(decision.tier()).isEqualTo(Tier.SKIP);
        assertThat(decision.reason()).startsWith(TierPolicy.EXEMPTION_REASON);
        assertThat(decision.reason()).contains("pol-api-1");
    }

    @Test
    void anAllowanceClauseCannotExcuseARuleItDoesNotName() {
        AnalysisUnit unit = unit(1, 0, "void save() {\n}\n", "void save() {\n    log.info(\"x\");\n}\n");
        List<StructuralChange> changes = List.of(StructuralChange.heuristic(
                StructuralChange.Kind.STATEMENTS_ADDED, 12, "1 statement(s) added", "", "log.info(\"x\");"));
        RuleContext context = new RuleContext(unit, changes);
        Rule gating = new Rule("OPERATOR-CHANGED", RiskCategory.SECURITY, true,
                "operators changed", ruleContext -> true);
        PolicyExemption exemption = new PolicyExemption("pol-api-1", 0.83,
                List.of("NULL-CHECK-REMOVED"));

        TierPolicy.Decision decision = policy.decide(context, List.of(new RuleHit(gating, List.of())),
                exemption);

        assertThat(decision.tier()).isEqualTo(Tier.MUST_INVESTIGATE);
    }

    private static AnalysisUnit unit(int added, int removed, String before, String after) {
        SourceUnit beforeUnit = new SourceUnit(SourceUnit.Kind.METHOD, "Sample.save()", 10, 14, before);
        SourceUnit afterUnit = new SourceUnit(SourceUnit.Kind.METHOD, "Sample.save()", 10, 14, after);
        return new AnalysisUnit("src/main/java/Sample.java", "METHOD#Sample.save()",
                beforeUnit, afterUnit, List.of(new FileChange.EditRange(10, 14, 10, 14)),
                added, removed);
    }
}
