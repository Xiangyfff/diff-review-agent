package com.codereview.agent.triage.plan;

import com.codereview.agent.triage.TriageService;
import com.codereview.agent.triage.diff.FileChange;
import com.codereview.agent.triage.diff.FileChange.ChangeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rendering contract: the plan has to be readable by a human and by the next stage,
 * without quietly copying every changed method into the output.
 */
class TriagePlanJsonTest {

    private static final String BEFORE = """
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
            class LoginService {
                String lookup(String user) {
                    return find(user);
                }
            }
            """;

    private TriagePlan plan() {
        FileChange change = new FileChange("LoginService.java", "LoginService.java", ChangeType.MODIFIED,
                List.of(new FileChange.EditRange(3, 5, 3, 3)), BEFORE, AFTER);
        return TriageService.offline().triage(List.of(change), "base", "head");
    }

    @Test
    void thePlanRendersAsJsonWithTheShapesAndTheTier() {
        String json = new TriagePlanJson().render(plan());

        assertThat(json).contains("\"baseRevision\"");
        assertThat(json).contains("MUST_INVESTIGATE");
        assertThat(json).contains("NULL_CHECK_REMOVED");
    }

    @Test
    void thePlanDoesNotCarryTheWholeMethodBody() {
        String json = new TriagePlanJson().render(plan());

        assertThat(json).doesNotContain("return find(user);");
        assertThat(json).doesNotContain("String lookup(String user)");
    }

    @Test
    void statsAreRenderedWithAReportedInvestigationRatio() {
        String json = new TriagePlanJson().render(plan());

        assertThat(json).contains("\"investigateRatio\"");
        assertThat(json).contains("\"unitsScheduled\"");
    }
}
