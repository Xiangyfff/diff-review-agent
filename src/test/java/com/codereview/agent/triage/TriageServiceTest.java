package com.codereview.agent.triage;

import com.codereview.agent.triage.diff.FileChange;
import com.codereview.agent.triage.diff.FileChange.ChangeType;
import com.codereview.agent.triage.index.SymbolIndex;
import com.codereview.agent.triage.model.ChangeOverview;
import com.codereview.agent.triage.model.IntentSummary;
import com.codereview.agent.triage.model.MockTriageModel;
import com.codereview.agent.triage.model.TriageModel;
import com.codereview.agent.triage.model.UnitAssessment;
import com.codereview.agent.triage.model.UnitPrompt;
import com.codereview.agent.triage.parse.SourceParser;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.plan.TriagePlan;
import com.codereview.agent.triage.rules.RuleCatalog;
import com.codereview.agent.triage.screen.Screening;
import com.codereview.agent.triage.tier.Tier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole gate, end to end, with the offline model. What matters here is that the rules
 * decide the tier, the budget truncates rather than fails, and a broken model still leaves
 * a usable plan.
 */
class TriageServiceTest {

    private static final String BEFORE = """
            package com.shop.auth;

            class LoginService {
                String lookup(String user) {
                    if (user == null) {
                        return null;
                    }
                    return find(user);
                }
            }
            """;

    private static final String AFTER = """
            package com.shop.auth;

            class LoginService {
                String lookup(String user) {
                    return find(user);
                }
            }
            """;

    private static FileChange nullGuardRemoval(String path) {
        return new FileChange(path, path, ChangeType.MODIFIED,
                List.of(new FileChange.EditRange(5, 7, 5, 5)), BEFORE, AFTER);
    }

    @Test
    void aRemovedNullGuardOnAnAuthPathIsScheduledWithoutAskingTheModel() {
        TriagePlan plan = TriageService.offline().triage(List.of(nullGuardRemoval("src/main/java/com/shop/auth/LoginService.java")),
                "base", "head");

        assertThat(plan.units()).hasSize(1);
        PlannedUnit unit = plan.units().get(0);
        assertThat(unit.tier()).isEqualTo(Tier.MUST_INVESTIGATE);
        assertThat(unit.scheduled()).isTrue();
        assertThat(plan.stats().unitsMust()).isEqualTo(1);
        assertThat(plan.stats().filesSeen()).isEqualTo(1);
        assertThat(plan.stats().filesScreenedOut()).isZero();
    }

    @Test
    void filesThatAreNotJavaNeverBecomeUnits() {
        FileChange readme = new FileChange("README.md", "README.md", ChangeType.MODIFIED,
                List.of(new FileChange.EditRange(1, 1, 1, 1)), "a", "b");

        TriagePlan plan = TriageService.offline().triage(List.of(readme), "base", "head");

        assertThat(plan.units()).isEmpty();
        assertThat(plan.stats().filesSeen()).isEqualTo(1);
        assertThat(plan.stats().filesScreenedOut()).isEqualTo(1);
    }

    @Test
    void theBudgetTruncatesThePlanInsteadOfFailing() {
        List<FileChange> changes = List.of(
                nullGuardRemoval("src/main/java/com/shop/auth/A.java"),
                nullGuardRemoval("src/main/java/com/shop/auth/B.java"),
                nullGuardRemoval("src/main/java/com/shop/auth/C.java"));
        TriageService service = new TriageService(new MockTriageModel(), new TriageConfig(1, 4, 8));

        TriagePlan plan = service.triage(changes, "base", "head");

        assertThat(plan.units()).hasSize(3);
        assertThat(plan.stats().unitsScheduled()).isEqualTo(1);
        assertThat(plan.stats().unitsBudgetSkipped()).isEqualTo(2);
        assertThat(plan.units()).filteredOn(PlannedUnit::budgetSkipped).hasSize(2);
    }

    @Test
    void aModelThatThrowsStillLeavesTheEvidenceBasedUnitScheduled() {
        TriageModel broken = new TriageModel() {
            @Override
            public UnitAssessment assess(UnitPrompt prompt) {
                throw new IllegalStateException("model unavailable");
            }

            @Override
            public IntentSummary summarize(ChangeOverview overview) {
                return IntentSummary.empty();
            }
        };
        TriageService service = new TriageService(broken, TriageConfig.defaults());

        TriagePlan plan = service.triage(List.of(nullGuardRemoval("src/main/java/com/shop/auth/A.java")), "base", "head");

        assertThat(plan.units()).hasSize(1);
        PlannedUnit unit = plan.units().get(0);
        assertThat(unit.tier()).isEqualTo(Tier.MUST_INVESTIGATE);
        assertThat(unit.scheduled()).isTrue();
        assertThat(unit.syntheticQuestions()).isTrue();
        assertThat(unit.questions()).isNotEmpty();
    }

    @Test
    void aUnitGatedByAFactStillGetsSomethingToInvestigate() {
        // Sensitive path gates on where the code lives, not on a shape, so the rule hit carries no
        // evidence. A model that answers with nothing used to leave this unit scheduled with an
        // empty question list, which the loop then reported as a conclusion-free unit.
        TriageModel silent = new TriageModel() {
            @Override
            public UnitAssessment assess(UnitPrompt prompt) {
                return UnitAssessment.declining("nothing to ask");
            }

            @Override
            public IntentSummary summarize(ChangeOverview overview) {
                return IntentSummary.empty();
            }
        };
        String before = "class LoginService {\n    void login(String name) {\n        log(name);\n    }\n}\n";
        String after = "class LoginService {\n    void login(String name) {\n        log(name);\n        audit(name);\n    }\n}\n";
        FileChange change = new FileChange("src/main/java/com/shop/auth/LoginService.java",
                "src/main/java/com/shop/auth/LoginService.java", ChangeType.MODIFIED,
                List.of(new FileChange.EditRange(3, 3, 3, 4)), before, after);

        TriagePlan plan = new TriageService(silent, TriageConfig.defaults())
                .triage(List.of(change), "base", "head");

        PlannedUnit unit = plan.units().get(0);
        assertThat(unit.tier()).isEqualTo(Tier.MUST_INVESTIGATE);
        assertThat(unit.questions()).isNotEmpty();
        assertThat(unit.syntheticQuestions()).isTrue();
    }

    @Test
    void statsAddUp() {
        TriagePlan plan = TriageService.offline().triage(
                List.of(nullGuardRemoval("src/main/java/com/shop/auth/A.java")), "base", "head");

        assertThat(plan.stats().unitsMust() + plan.stats().unitsJudge() + plan.stats().unitsSkip())
                .isEqualTo(plan.stats().unitsTotal());
        assertThat(plan.stats().summary()).isNotBlank();
    }

    @Test
    void repositoryFactsReachThePromptAsHintsNotVerdicts() {
        List<UnitPrompt> seen = new ArrayList<>();
        TriageModel capturing = new TriageModel() {
            @Override
            public UnitAssessment assess(UnitPrompt prompt) {
                seen.add(prompt);
                return new UnitAssessment(List.of(), true, "", "");
            }

            @Override
            public IntentSummary summarize(ChangeOverview overview) {
                return IntentSummary.empty();
            }
        };
        String file = "src/main/java/com/shop/auth/LoginService.java";
        SymbolIndex index = new SymbolIndex(
                Map.of("lookup", Set.of(file, "src/main/java/com/shop/Caller.java")),
                Map.of("lookup", new SymbolIndex.Declaration("lookup", file, 4, true, 1)),
                2, 0, false);
        TriageService service = new TriageService(capturing, TriageConfig.defaults(),
                new Screening(), new SourceParser(), new RuleCatalog(RuleCatalog.defaults().rules()), index);

        service.triage(List.of(nullGuardRemoval(file)), "base", "head");

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).facts()).contains("另外 1 个源文件").contains("public");
        assertThat(seen.get(0).render()).contains("背景事实").contains("不能当成结论");
    }
}
