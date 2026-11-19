package com.codereview.agent.triage.model;

/**
 * The only thing the triage stage knows how to ask a language model.
 *
 * <p>Two methods, both total and both side-effect free. A language-model client, a cached
 * replay and the offline mock are interchangeable behind this interface, and the whole
 * triage stage can be tested without a network call because of it.
 */
public interface TriageModel {

    UnitAssessment assess(UnitPrompt prompt);

    IntentSummary summarize(ChangeOverview overview);
}
