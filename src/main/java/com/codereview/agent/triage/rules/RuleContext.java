package com.codereview.agent.triage.rules;

import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.unit.AnalysisUnit;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Everything a rule is allowed to look at. Rules never call a model and never parse. */
public record RuleContext(AnalysisUnit unit, List<StructuralChange> changes) {

    public RuleContext {
        changes = List.copyOf(changes);
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
