package com.codereview.agent.triage;

import com.codereview.agent.triage.diff.FileChange;
import com.codereview.agent.triage.diff.FileChange.ChangeType;
import com.codereview.agent.triage.model.ChangeOverview;
import com.codereview.agent.triage.model.IntentSummary;
import com.codereview.agent.triage.model.MockTriageModel;
import com.codereview.agent.triage.model.TriageModel;
import com.codereview.agent.triage.model.UnitAssessment;
import com.codereview.agent.triage.model.UnitPrompt;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.plan.TriagePlan;
import com.codereview.agent.triage.tier.Tier;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void statsAddUp() {
        TriagePlan plan = TriageService.offline().triage(
                List.of(nullGuardRemoval("src/main/java/com/shop/auth/A.java")), "base", "head");

        assertThat(plan.stats().unitsMust() + plan.stats().unitsJudge() + plan.stats().unitsSkip())
                .isEqualTo(plan.stats().unitsTotal());
        assertThat(plan.stats().summary()).isNotBlank();
    }
}
