package com.codereview.agent.triage.rules;

import com.codereview.agent.triage.structure.StructuralChange;

import java.util.List;

/** A rule that fired, with the exact changes that made it fire. */
public record RuleHit(Rule rule, List<StructuralChange> evidence) {

    public RuleHit {
        evidence = List.copyOf(evidence);
    }

    public boolean isMandatory() {
        return rule.mandatory();
    }

    /** A mandatory rule only gates when its evidence is certain, or when the rule reads facts only. */
    public boolean gates() {
        return rule.mandatory()
                && (evidence.isEmpty() || evidence.stream()
                        .allMatch(change -> change.confidence() == StructuralChange.Confidence.EXACT));
    }
}
