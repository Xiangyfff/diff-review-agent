package com.codereview.agent.triage.tier;

import com.codereview.agent.triage.rules.RiskCategory;
import com.codereview.agent.triage.rules.RuleContext;
import com.codereview.agent.triage.rules.RuleHit;
import com.codereview.agent.triage.unit.AnalysisUnit;
import com.codereview.agent.triage.unit.SourceUnit;

import java.util.Comparator;
import java.util.List;

/**
 * Decides the tier from rules alone.
 *
 * <p>The model is not consulted here on purpose. If the decision "is this worth a closer
 * look" were left to the model, the cheap gate in front of the expensive stage would be as
 * expensive as the stage itself, and nothing about it would be reproducible.
 */
public final class TierPolicy {

    public record Decision(Tier tier, RiskCategory category, String reason) {

        public boolean investigate() {
            return tier == Tier.MUST_INVESTIGATE;
        }
    }

    public Decision decide(RuleContext context, List<RuleHit> hits) {
        RiskCategory category = hits.stream()
                .map(hit -> hit.rule().category())
                .min(Comparator.naturalOrder())
                .orElse(RiskCategory.LOGIC);

        if (context.changes().isEmpty()) {
            return new Decision(Tier.SKIP, category, "no structural change detected");
        }
        if (importsOnly(context.unit())) {
            return new Decision(Tier.SKIP, RiskCategory.DOCS, "only imports changed");
        }
        RuleHit gating = hits.stream().filter(RuleHit::gates).findFirst().orElse(null);
        if (gating != null) {
            return new Decision(Tier.MUST_INVESTIGATE, category,
                    "rule " + gating.rule().id() + " requires investigation: " + gating.rule().rationale());
        }
        if (hits.isEmpty()) {
            return new Decision(Tier.JUDGE, category, "no rule matched; the model decides");
        }
        return new Decision(Tier.JUDGE, category,
                "only advisory rules matched: " + hits.stream().map(hit -> hit.rule().id()).toList());
    }

    /**
     * An import-only hunk is noise: the import alone changes no behaviour, and the code that
     * starts using it arrives as its own unit. Asking a model to justify an import produced a
     * question about "new entry points" for a line of imports, which is exactly the kind of
     * comment that makes a review bot tiring to read.
     */
    private static boolean importsOnly(AnalysisUnit unit) {
        if (unit.kind() != SourceUnit.Kind.TYPE_LEVEL || unit.removedLines() > 0) {
            return false;
        }
        String text = unit.textAfter();
        if (text.isBlank()) {
            return false;
        }
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .allMatch(line -> line.startsWith("import "));
    }
}
