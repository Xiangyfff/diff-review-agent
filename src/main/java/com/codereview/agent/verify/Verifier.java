package com.codereview.agent.verify;

/**
 * A critic. It gets one finding and a bounded tool session, and it has to decide whether the
 * finding stands. Implementations must not retract anything they cannot back with evidence they
 * collected in that session; the runner checks that, but a critic that has to be caught by the
 * runner is a badly written critic.
 */
@FunctionalInterface
public interface Verifier {

    Verdict review(VerificationRequest request, ToolSession session);
}
