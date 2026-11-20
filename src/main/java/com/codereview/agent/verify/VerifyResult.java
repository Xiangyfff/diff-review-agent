package com.codereview.agent.verify;

import com.codereview.agent.investigate.Finding;

/**
 * The finding plus what the critic did with it. A disposition is what downstream rendering uses:
 * an assertion is published as a statement, a question is published as a question, and a dropped
 * finding never reaches the comment at all.
 */
public record VerifyResult(Finding finding, Verdict verdict, Disposition disposition,
        boolean invalid, int toolCalls) {

    public enum Disposition {
        ASSERTION,
        QUESTION,
        DROPPED
    }

    /** The critic said something that could not be backed by the evidence it collected. */
    public static VerifyResult invalid(Finding finding, Verdict verdict, int toolCalls) {
        return new VerifyResult(finding, verdict, Disposition.ASSERTION, true, toolCalls);
    }

    /** The critic crashed; that is not a refutation, so the finding keeps its disposition. */
    public static VerifyResult modelFailed(Finding finding, String reason, int toolCalls) {
        return new VerifyResult(finding, new Verdict.Keep(reason), Disposition.ASSERTION, false,
                toolCalls);
    }

    public static VerifyResult of(Finding finding, Verdict verdict, int toolCalls) {
        Disposition disposition = switch (verdict) {
            case Verdict.Keep keep -> Disposition.ASSERTION;
            case Verdict.Downgrade downgrade -> Disposition.QUESTION;
            case Verdict.Retract retract -> Disposition.DROPPED;
        };
        return new VerifyResult(finding, verdict, disposition, false, toolCalls);
    }
}
