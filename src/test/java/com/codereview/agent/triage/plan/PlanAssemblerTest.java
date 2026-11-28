package com.codereview.agent.triage.plan;

import com.codereview.agent.triage.model.UnitAssessment;
import com.codereview.agent.triage.model.Witness;
import com.codereview.agent.triage.rules.RiskCategory;
import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.tier.Tier;
import com.codereview.agent.triage.unit.AnalysisUnit;
import com.codereview.agent.triage.unit.SourceUnit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skip path is the one place where a model's impression can remove a unit from review, so the
 * checks that guard it are worth testing on their own: the witness has to describe something the
 * program can see, and a run cannot be emptied by a model that declines everything.
 */
class PlanAssemblerTest {

    private static final String FILE = "src/main/java/com/example/A.java";

    private static AnalysisUnit unit(String file) {
        SourceUnit after = new SourceUnit(SourceUnit.Kind.METHOD, "a", 10, 12,
                "void a() {\n    log.info(\"kept\");\n}");
        return new AnalysisUnit(file, "METHOD#a", null, after, List.of(), 1, 0);
    }

    private static PlanAssembler.Candidate judge(String file, UnitAssessment assessment) {
        return new PlanAssembler.Candidate(unit(file), Tier.JUDGE, RiskCategory.DOCS,
                "no hard rule matched", List.of(StructuralChange.exact(
                        StructuralChange.Kind.LITERAL_CHANGED, 11, "log text changed",
                        "log.info(\"old\")", "log.info(\"kept\")")),
                List.of(), assessment, false, "");
    }

    private static UnitAssessment declining(Witness witness) {
        return new UnitAssessment(List.of(), false, "只改了日志文案", "", witness);
    }

    private static List<PlanAssembler.Candidate> judges(String directory, UnitAssessment assessment) {
        List<PlanAssembler.Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            candidates.add(judge(directory + "/A" + index + ".java", assessment));
        }
        return candidates;
    }

    @Test
    void aDeclineWithACheckableLogWitnessMaySkipTheUnit() {
        PlanAssembler.Result result = new PlanAssembler().assemble(
                judges("src/main/java/com/example",
                        declining(new Witness(Witness.LOG_ONLY, "log.info(\"kept\")"))),
                20);

        assertThat(result.modelSkips()).isEqualTo(1);
        assertThat(result.units().stream().filter(planned -> planned.tier() == Tier.SKIP)
                .toList()).singleElement().satisfies(planned -> {
                    assertThat(planned.tierReason()).startsWith(PlanAssembler.MODEL_DECLINED_REASON);
                    assertThat(planned.questions()).isEmpty();
                });
    }

    @Test
    void aDeclineWhoseQuoteIsNotALogCallKeepsTheUnitInReview() {
        PlanAssembler.Result result = new PlanAssembler().assemble(
                judges("src/main/java/com/example",
                        declining(new Witness(Witness.LOG_ONLY, "void a()"))),
                20);

        assertThat(result.modelSkips()).isZero();
        assertThat(result.units()).allSatisfy(planned ->
                assertThat(planned.tier()).isEqualTo(Tier.JUDGE));
    }

    @Test
    void aDeclineWithoutAWitnessKeepsTheUnitInReview() {
        PlanAssembler.Result result = new PlanAssembler().assemble(
                judges("src/main/java/com/example", declining(Witness.NONE)), 20);

        assertThat(result.modelSkips()).isZero();
        assertThat(result.units()).allSatisfy(planned ->
                assertThat(planned.tier()).isEqualTo(Tier.JUDGE));
    }

    @Test
    void aTestOnlyWitnessIsCheckedAgainstThePath() {
        PlanAssembler.Result result = new PlanAssembler().assemble(
                judges("src/test/java/com/example", declining(new Witness(Witness.TEST_ONLY, ""))),
                20);

        assertThat(result.modelSkips()).isEqualTo(1);
    }

    @Test
    void aModelCannotDeclineMoreThanItsShareOfJudgeUnits() {
        List<PlanAssembler.Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            candidates.add(judge("src/main/java/com/example/A" + index + ".java",
                    declining(new Witness(Witness.LOG_ONLY, "log.info(\"kept\")"))));
        }

        PlanAssembler.Result result = new PlanAssembler().assemble(candidates, 20);

        // 30% of four judge units is one; the other three stay in review.
        assertThat(result.modelSkips()).isEqualTo(1);
        assertThat(result.units().stream().filter(planned -> planned.tier() == Tier.JUDGE))
                .hasSize(3);
    }
}
