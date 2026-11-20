package com.codereview.agent.investigate;

/**
 * The model side of the loop, kept as thin as possible.
 *
 * <p>Everything framework-specific lives behind this interface: the offline model used by tests,
 * the mock used in demos, and the LangChain4j-backed one all implement the same method. The loop
 * itself knows nothing about prompts or HTTP.
 */
public interface InvestigationModel {

    ModelTurn next(InvestigationState state);
}
