package com.codereview.agent.triage.model;

import java.util.List;

/**
 * The second, cheaper model call of triage: one look at the whole change.
 *
 * <p>It answers two questions a per-unit call cannot: what the change is trying to do, and
 * which concerns span files. The intent sentence is what makes a review comment read like a
 * reviewer rather than a linter, and the cross-file concerns are the only place where a
 * change to one file can raise a question about another.
 */
public record IntentSummary(String intent, List<String> crossFileConcerns) {

    public static IntentSummary empty() {
        return new IntentSummary("", List.of());
    }

    public IntentSummary {
        intent = intent == null ? "" : intent;
        crossFileConcerns = List.copyOf(crossFileConcerns);
    }
}
