package com.codereview.agent.triage.rules;

import java.util.function.Predicate;

/**
 * A hand-written detector for one shape of risky change.
 *
 * <p>{@code mandatory} means: if this rule fires on evidence that is certain, the unit is
 * investigated no matter what the model says. That is the point of the rule layer. Rules
 * cover shapes where the syntax tree already knows the answer, so a model that misses the
 * change cannot silently downgrade it.
 *
 * <p>Rules are advisory by default. A rule that fires on a guess (a resource type matched by
 * name, say) must not be marked mandatory, or one bad name match would buy an expensive
 * investigation.
 */
public record Rule(
        String id,
        RiskCategory category,
        boolean mandatory,
        String rationale,
        Predicate<RuleContext> matches) {
}
