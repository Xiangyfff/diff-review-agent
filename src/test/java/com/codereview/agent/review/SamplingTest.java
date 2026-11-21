package com.codereview.agent.review;

import com.codereview.agent.triage.unit.AnalysisUnit;
import com.codereview.agent.triage.unit.SourceUnit;
import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.tier.Tier;
import com.codereview.agent.triage.rules.RiskCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplingTest {

    @Test
    void offNeverTakesAnything() {
        assertThat(Sampling.OFF.takes("a#b")).isFalse();
        assertThat(Sampling.OFF.pick(List.of(), List.of())).isEmpty();
    }

    @Test
    void theSameUnitIsAlwaysInOrOut() {
        Sampling sampling = Sampling.percent(20);
        boolean first = sampling.takes("src/A.java#METHOD#a()");
        for (int i = 0; i < 20; i++) {
            assertThat(sampling.takes("src/A.java#METHOD#a()")).isEqualTo(first);
        }
    }

    @Test
    void aLargerRateTakesMoreUnits() {
        int small = taken(5);
        int large = taken(50);
        assertThat(large).isGreaterThan(small);
    }

    @Test
    void onlyUnitsThePlanLeftAloneAreCandidates() {
        PlannedUnit scheduled = unit("a", Tier.MUST_INVESTIGATE, true);
        PlannedUnit skipped = unit("b", Tier.SKIP, false);
        PlannedUnit unparsed = unit("c", Tier.SKIP, false);

        List<PlannedUnit> picked = Sampling.percent(100).pick(
                List.of(scheduled, skipped, unparsed), List.of(scheduled));

        assertThat(picked).extracting(unit -> unit.unit().name()).containsExactly("b", "c");
    }

    @Test
    void theEstimateIsTheShareThatProducedFindings() {
        Sampling.Summary summary = new Sampling.Summary(0.1, 17, 40, 4, 1);
        assertThat(summary.estimate()).isEqualTo(0.25);
        assertThat(summary.describe()).contains("抽中 4 个").contains("25%");
    }

    @Test
    void rejectsRatesOutsideTheUnitInterval() {
        assertThatThrownBy(() -> Sampling.percent(120))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int taken(double percent) {
        Sampling sampling = Sampling.percent(percent);
        int count = 0;
        for (int i = 0; i < 200; i++) {
            if (sampling.takes("src/A.java#METHOD#m" + i)) {
                count++;
            }
        }
        return count;
    }

    private static PlannedUnit unit(String name, Tier tier, boolean scheduled) {
        SourceUnit source = new SourceUnit(SourceUnit.Kind.METHOD, name, 1, 5, "void " + name + "() {}");
        AnalysisUnit analysis = new AnalysisUnit("src/A.java", "METHOD#" + name, source, source,
                List.of(), 1, 0);
        return new PlannedUnit(analysis, tier, RiskCategory.LOGIC, "test", List.of(), List.of(),
                List.of(), false, "", false, false, "");
    }
}
