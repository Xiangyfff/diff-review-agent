package com.codereview.agent.triage.rules;

import com.codereview.agent.triage.policy.PolicyHit;
import com.codereview.agent.triage.staticanalysis.StaticWarning;
import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.unit.AnalysisUnit;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Everything a rule is allowed to look at. Rules never call a model and never parse. */
public record RuleContext(AnalysisUnit unit, List<StructuralChange> changes,
        List<StaticWarning> warnings, List<PolicyHit> policies) {

    public RuleContext(AnalysisUnit unit, List<StructuralChange> changes) {
        this(unit, changes, List.of(), List.of());
    }

    public RuleContext(AnalysisUnit unit, List<StructuralChange> changes, List<StaticWarning> warnings) {
        this(unit, changes, warnings, List.of());
    }

    public RuleContext {
        changes = List.copyOf(changes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        policies = policies == null ? List.of() : List.copyOf(policies);
    }

    /** Only an enforceable clause counts here: a reference clause is background, not a gate. */
    public boolean policyMandatory() {
        return policies.stream().anyMatch(hit -> hit.chunk().enforceable());
    }

    public boolean warned() {
        return !warnings.isEmpty();
    }

    public List<StaticWarning> warningsOf(String... kinds) {
        java.util.Set<String> wanted = java.util.Set.of(kinds);
        return warnings.stream().filter(warning -> wanted.contains(warning.kind())).toList();
    }

    public String path() {
        return unit.file();
    }

    public Set<StructuralChange.Kind> kinds() {
        return changes.stream().map(StructuralChange::kind).collect(Collectors.toSet());
    }

    public boolean has(StructuralChange.Kind kind) {
        return changes.stream().anyMatch(change -> change.kind() == kind);
    }

    public List<StructuralChange> of(StructuralChange.Kind kind) {
        return changes.stream().filter(change -> change.kind() == kind).toList();
    }
}
