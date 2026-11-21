package com.codereview.agent.review;

import com.codereview.agent.triage.plan.PlannedUnit;
import com.codereview.agent.triage.tier.Tier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A share of the units the plan left alone, checked anyway.
 *
 * <p>A skipped unit is not evidence of a clean change, it is an unmeasured one. Taking a small,
 * deterministic sample of them and investigating it regardless turns "we skipped 30 units" into a
 * rate, at the price of the model calls on that sample. The choice is a hash of the unit key, so
 * two runs over the same commit review the same sample and the rate means the same thing twice.
 */
public record Sampling(double rate, int seed) {

    public static final Sampling OFF = new Sampling(0.0, 17);

    private static final long BUCKETS = 10_000L;

    public Sampling {
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("rate must be within [0, 1]");
        }
    }

    public static Sampling percent(double percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent must be within [0, 100]");
        }
        return new Sampling(percent / 100.0, 17);
    }

    public boolean takes(String unitKey) {
        if (rate <= 0.0) {
            return false;
        }
        long bucket = Math.floorMod(unitKey.hashCode() * 31L + seed, BUCKETS);
        return bucket < Math.round(rate * BUCKETS);
    }

    /**
     * The units worth sampling: everything the plan did not schedule, minus the ones no amount of
     * looking could judge (a unit whose text did not parse has no code to read).
     */
    public List<PlannedUnit> pick(List<PlannedUnit> all, List<PlannedUnit> scheduled) {
        if (rate <= 0.0) {
            return List.of();
        }
        Set<String> known = new HashSet<>();
        for (PlannedUnit unit : scheduled) {
            known.add(unit.unit().file() + "#" + unit.unit().key());
        }
        List<PlannedUnit> picked = new ArrayList<>();
        for (PlannedUnit unit : all) {
            if (unit.scheduled() || unit.parseFailed() || unit.tier() != Tier.SKIP
                    && unit.tier() != Tier.JUDGE) {
                continue;
            }
            String id = unit.unit().file() + "#" + unit.unit().key();
            if (!known.add(id)) {
                continue;
            }
            if (takes(id)) {
                picked.add(unit);
            }
        }
        return picked;
    }

    /**
     * What the sample implies, reported next to the comments: how many units were candidates, how
     * many were checked, and how many of those produced a conclusion that survived the verifier.
     * The rate is an estimate, not a measurement — the sample is small by design.
     */
    public record Summary(double rate, int seed, int candidates, int sampled, int withFindings) {

        public static Summary none() {
            return new Summary(0.0, 0, 0, 0, 0);
        }

        public double estimate() {
            return sampled == 0 ? 0.0 : (double) withFindings / sampled;
        }

        public String describe() {
            if (rate <= 0.0) {
                return "未开启抽查";
            }
            if (sampled == 0) {
                return "抽查 " + percentText() + "：候选 " + candidates + " 个单元，本次没有抽中";
            }
            return "抽查 " + percentText() + "：候选 " + candidates + " 个单元，抽中 " + sampled
                    + " 个，其中 " + withFindings + " 个查出了带证据的结论（估计约 "
                    + Math.round(estimate() * 100) + "%）";
        }

        private String percentText() {
            return Math.round(rate * 100) + "%";
        }
    }
}
