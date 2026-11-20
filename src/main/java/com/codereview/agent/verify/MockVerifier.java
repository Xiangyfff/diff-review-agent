package com.codereview.agent.verify;

import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;

import java.util.List;
import java.util.Map;

/**
 * Offline, deterministic critic: it reads the lines around the finding and retracts only when that
 * text contains a construct that would make the claim impossible (a non-null check or a
 * non-null assertion). It never retracts on a hunch, which is the behaviour the real model is
 * supposed to reproduce and the tests pin down.
 */
public final class MockVerifier implements Verifier {

    private static final List<String> NULL_PROOF = List.of("requireNonNull", "checkNotNull",
            "!= null", "Objects.requireNonNull");

    @Override
    public Verdict review(VerificationRequest request, ToolSession session) {
        if (session.callsLeft() < 1) {
            return new Verdict.Keep("没有可用的工具预算，无法反驳");
        }
        int line = Math.max(1, request.finding().line());
        ToolOutcome outcome = session.run(new ToolCall("read_lines", Map.of(
                "path", request.finding().file(),
                "start", Integer.toString(Math.max(1, line - 4)),
                "end", Integer.toString(line + 4))));
        if (!outcome.ok() || outcome.excerpts().isEmpty()) {
            return new Verdict.Keep("附近代码读不到，维持原结论");
        }
        String text = outcome.excerpts().get(0).text();
        boolean proof = NULL_PROOF.stream().anyMatch(text::contains);
        if (!proof) {
            return new Verdict.Keep("读到的附近代码里没有能推翻它的证据");
        }
        String id = session.evidence().get(session.evidence().size() - 1).id();
        return new Verdict.Retract("证据显示这个位置上已经有非空检查或非空断言", List.of(id));
    }
}
