package com.codereview.agent.review;

import java.util.List;

/**
 * One comment the agent would post on a diff.
 *
 * <p>A comment always carries the evidence it was built from. That is the whole point of the
 * pipeline: nothing reaches this record without having survived verification against evidence the
 * agent actually read.
 */
public record Comment(String file, int line, String riskCategory, Status status, String body,
        List<String> evidenceIds) {

    public enum Status {
        /** Stated as fact: the evidence supports the claim. */
        ASSERTION,
        /** Downgraded by the verifier: the observation is real but the conclusion is not proven. */
        QUESTION
    }

    public Comment {
        evidenceIds = List.copyOf(evidenceIds);
    }

    public String render() {
        return file + ":" + line + " [" + riskCategory + "] " + body
                + "\n    evidence: " + String.join(", ", evidenceIds);
    }
}
