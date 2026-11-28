package com.codereview.agent.triage.tier;

import com.codereview.agent.triage.policy.PolicyExemption;
import com.codereview.agent.triage.rules.RiskCategory;
import com.codereview.agent.triage.rules.RuleContext;
import com.codereview.agent.triage.rules.RuleHit;
import com.codereview.agent.triage.structure.StructuralChange;
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

    /**
     * Prefix of the reason recorded when the cheap gate, rather than the model, decided a unit was
     * not worth a look. It is a prefix rather than a flag so the plan stays a flat, readable record
     * and the count can be recovered from it.
     */
    public static final String CHEAP_GATE_REASON = "cheap gate: ";

    /**
     * Prefix of the reason recorded when a policy clause explicitly allows what the rules would
     * otherwise have promoted. It is a prefix for the same reason as the cheap gate: the plan
     * stays flat, and the count survives serialisation without a second field.
     */
    public static final String EXEMPTION_REASON = "policy exemption: ";

    /** Above this many changed lines, "small" stops being a defensible description. */
    private static final int SMALL_CHANGE_LINES = 4;

    public record Decision(Tier tier, RiskCategory category, String reason) {

        public boolean investigate() {
            return tier == Tier.MUST_INVESTIGATE;
        }
    }

    public Decision decide(RuleContext context, List<RuleHit> hits) {
        return decide(context, hits, null);
    }

    /**
     * @param exemption an allowance clause that removed hits for {@code context.unit().file()},
     *                  or {@code null}. It only skips a unit when no gating rule remains: a
     *                  clause may excuse the rule it names, never the other rules that fired.
     */
    public Decision decide(RuleContext context, List<RuleHit> hits, PolicyExemption exemption) {
        Decision decision = decideByRules(context, hits);
        if (exemption != null && hits.stream().noneMatch(RuleHit::gates)) {
            return new Decision(Tier.SKIP, decision.category(),
                    EXEMPTION_REASON + exemption.describe());
        }
        return decision;
    }

    private Decision decideByRules(RuleContext context, List<RuleHit> hits) {
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
            if (smallAndShapeless(context)) {
                return new Decision(Tier.SKIP, category, CHEAP_GATE_REASON
                        + "a small change whose parts have no recognised shape");
            }
            return new Decision(Tier.JUDGE, category, "no rule matched; the model decides");
        }
        return new Decision(Tier.JUDGE, category,
                "only advisory rules matched: " + hits.stream().map(hit -> hit.rule().id()).toList());
    }

    /**
     * The cheap gate: a handful of changed lines where every part is an unrecognised leftover — a
     * statement added, a statement removed, no shape worth naming.
     *
     * <p>It is deliberately conservative. A mandatory rule still wins, and so does an advisory one:
     * a one-line condition flip is exactly the small change that must not be waved through. The gate
     * only fires when nothing at all was recognised, which is the case that used to burn a model call
     * to be told "nothing here". Anything bigger, or anything whose shape has a name, still goes to
     * the model.
     */
    private static boolean smallAndShapeless(RuleContext context) {
        AnalysisUnit unit = context.unit();
        if (unit.addedLines() + unit.removedLines() > SMALL_CHANGE_LINES) {
            return false;
        }
        return context.changes().stream().allMatch(change ->
                change.kind() == StructuralChange.Kind.STATEMENTS_ADDED
                        || change.kind() == StructuralChange.Kind.STATEMENTS_DELETED);
    }

    public static boolean isCheapGate(String reason) {
        return reason != null && reason.startsWith(CHEAP_GATE_REASON);
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
