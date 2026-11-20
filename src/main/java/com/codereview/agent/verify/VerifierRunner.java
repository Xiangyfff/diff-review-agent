package com.codereview.agent.verify;

import com.codereview.agent.investigate.Evidence;
import com.codereview.agent.investigate.Toolbox;

import java.util.List;

/**
 * Runs one critic pass over one finding and enforces the rule that a refutation must be backed by
 * evidence collected in that very pass. A critic that retracts without proving anything is counted
 * as invalid and the finding is kept, so a lazy or hallucinating critic cannot quietly delete real
 * findings.
 */
public final class VerifierRunner {

    private final Verifier verifier;
    private final Toolbox toolbox;
    private final VerifyConfig config;

    public VerifierRunner(Verifier verifier, Toolbox toolbox, VerifyConfig config) {
        this.verifier = verifier;
        this.toolbox = toolbox;
        this.config = config;
    }

    public VerifyResult review(VerificationRequest request) {
        ToolSession session = new ToolSession(toolbox, config.maxToolCalls());
        Verdict verdict;
        try {
            verdict = verifier.review(request, session);
        } catch (RuntimeException failure) {
            return VerifyResult.modelFailed(request.finding(),
                    "复核模型调用失败：" + failure.getClass().getSimpleName(), session.calls());
        }
        if (verdict == null) {
            return VerifyResult.modelFailed(request.finding(), "复核模型没有给出裁决",
                    session.calls());
        }
        if (verdict instanceof Verdict.Retract || verdict instanceof Verdict.Downgrade) {
            List<String> ids = verdict.counterEvidenceIds();
            boolean backed = ids != null && !ids.isEmpty()
                    && ids.stream().allMatch(id -> lookup(session, id));
            if (!backed) {
                return VerifyResult.invalid(request.finding(), verdict, session.calls());
            }
        }
        return VerifyResult.of(request.finding(), verdict, session.calls());
    }

    private static boolean lookup(ToolSession session, String id) {
        for (Evidence evidence : session.evidence()) {
            if (evidence.id().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
