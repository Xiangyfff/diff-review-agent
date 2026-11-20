package com.codereview.agent.review;

import com.codereview.agent.investigate.InvestigationModel;
import com.codereview.agent.investigate.Toolbox;
import com.codereview.agent.triage.model.TriageModel;
import com.codereview.agent.verify.Verifier;

/**
 * Builds the three stage models for one run.
 *
 * <p>The investigation model needs the toolbox, which only exists once the repository is known, so
 * the pipeline asks for the models after it has built the tools rather than before. Everything else
 * about a model stays behind this seam: the offline stand-ins and a real provider are the same three
 * objects to the pipeline.
 */
public interface ModelFactory {

    TriageModel triage();

    InvestigationModel investigation(Toolbox toolbox);

    Verifier verifier();

    /** What to print in a report, never including a key. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
