package com.codereview.agent.verify;

import com.codereview.agent.investigate.Evidence;
import com.codereview.agent.investigate.Finding;
import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;
import com.codereview.agent.investigate.Toolbox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerifierRunnerTest {

    private static final Finding FINDING = new Finding("METHOD#A.b()", "A.java", 12,
            "`value` 在这里可能为空", List.of("ev-1"));

    private static Toolbox toolboxReturning(String text) {
        ReviewTool tool = new ReviewTool() {
            @Override
            public String name() {
                return "read_lines";
            }

            @Override
            public String purpose() {
                return "read lines";
            }

            @Override
            public String arguments() {
                return "path, start, end";
            }

            @Override
            public ToolOutcome run(ToolCall call) {
                return ToolOutcome.of("read 3 lines", List.of(
                        new ToolOutcome.Excerpt("A.java", 10, 12, text)));
            }
        };
        return new Toolbox(List.of(tool));
    }

    private static VerificationRequest request() {
        return new VerificationRequest(FINDING,
                List.of(new Evidence("ev-1", "read_lines", "A.java", 10, 12,
                        "12: value.length()")),
                "void b() { value.length(); }", "void b() { value.length(); }",
                List.of("read_lines A.java:10-14 -> 1 excerpt(s)"));
    }

    @Test
    void keepLeavesTheFindingAsAnAssertion() {
        VerifierRunner runner = new VerifierRunner((request, session) -> new Verdict.Keep("没有找到反证"),
                toolboxReturning("12: value.length()"), VerifyConfig.defaults());

        VerifyResult result = runner.review(request());

        assertThat(result.disposition()).isEqualTo(VerifyResult.Disposition.ASSERTION);
        assertThat(result.invalid()).isFalse();
        assertThat(result.verdict()).isInstanceOf(Verdict.Keep.class);
    }

    @Test
    void retractWithEvidenceCollectedInThisRoundDropsTheFinding() {
        VerifierRunner runner = new VerifierRunner((request, session) -> {
            session.run(new ToolCall("read_lines", java.util.Map.of("path", "A.java")));
            String id = session.evidence().get(0).id();
            return new Verdict.Retract("上游已经有非空检查", List.of(id));
        }, toolboxReturning("10: if (value != null) {"), VerifyConfig.defaults());

        VerifyResult result = runner.review(request());

        assertThat(result.disposition()).isEqualTo(VerifyResult.Disposition.DROPPED);
        assertThat(result.invalid()).isFalse();
        assertThat(result.toolCalls()).isEqualTo(1);
    }

    @Test
    void retractWithoutEvidenceIsInvalidAndKeepsTheFinding() {
        VerifierRunner runner = new VerifierRunner(
                (request, session) -> new Verdict.Retract("我觉得不对", List.of()),
                toolboxReturning("10: if (value != null) {"), VerifyConfig.defaults());

        VerifyResult result = runner.review(request());

        assertThat(result.invalid()).isTrue();
        assertThat(result.disposition()).isEqualTo(VerifyResult.Disposition.ASSERTION);
    }

    @Test
    void retractCitingEvidenceTheCriticNeverSawIsInvalid() {
        VerifierRunner runner = new VerifierRunner(
                (request, session) -> new Verdict.Retract("看过了", List.of("ev-99")),
                toolboxReturning("10: if (value != null) {"), VerifyConfig.defaults());

        VerifyResult result = runner.review(request());

        assertThat(result.invalid()).isTrue();
        assertThat(result.disposition()).isEqualTo(VerifyResult.Disposition.ASSERTION);
    }

    @Test
    void downgradeBecomesAQuestion() {
        VerifierRunner runner = new VerifierRunner((request, session) -> {
            session.run(new ToolCall("read_lines", java.util.Map.of("path", "A.java")));
            String id = session.evidence().get(0).id();
            return new Verdict.Downgrade("证据只支持「需要确认」，不支持「已经坏了」", List.of(id));
        }, toolboxReturning("10: value.size()"), VerifyConfig.defaults());

        VerifyResult result = runner.review(request());

        assertThat(result.disposition()).isEqualTo(VerifyResult.Disposition.QUESTION);
    }

    @Test
    void aCriticThatThrowsDoesNotDeleteTheFinding() {
        VerifierRunner runner = new VerifierRunner((request, session) -> {
            throw new IllegalStateException("model down");
        }, toolboxReturning("10: value"), VerifyConfig.defaults());

        VerifyResult result = runner.review(request());

        assertThat(result.disposition()).isEqualTo(VerifyResult.Disposition.ASSERTION);
        assertThat(result.verdict().reason()).contains("IllegalStateException");
    }

    @Test
    void aCriticCannotSpendMoreCallsThanItsBudget() {
        VerifierRunner runner = new VerifierRunner((request, session) -> {
            ToolOutcome first = session.run(new ToolCall("read_lines", java.util.Map.of("path", "A.java")));
            ToolOutcome second = session.run(new ToolCall("read_lines", java.util.Map.of("path", "A.java")));
            assertThat(first.ok()).isTrue();
            assertThat(second.ok()).isFalse();
            return new Verdict.Keep("only one call was allowed");
        }, toolboxReturning("10: value"), new VerifyConfig(1));

        VerifyResult result = runner.review(request());

        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.disposition()).isEqualTo(VerifyResult.Disposition.ASSERTION);
    }

    @Test
    void anUnknownToolIsReportedInsteadOfCrashing() {
        VerifierRunner runner = new VerifierRunner((request, session) -> {
            ToolOutcome outcome = session.run(new ToolCall("delete_everything", java.util.Map.of()));
            assertThat(outcome.ok()).isFalse();
            return new Verdict.Keep("tool was rejected");
        }, toolboxReturning("10: value"), VerifyConfig.defaults());

        VerifyResult result = runner.review(request());

        assertThat(result.toolCalls()).isZero();
        assertThat(result.disposition()).isEqualTo(VerifyResult.Disposition.ASSERTION);
    }

    @Test
    void theMockCriticRetractsOnlyWhenItReadsARealNullCheck() {
        VerifierRunner runner = new VerifierRunner(new MockVerifier(),
                toolboxReturning("10: if (value != null) {"), VerifyConfig.defaults());
        assertThat(runner.review(request()).disposition())
                .isEqualTo(VerifyResult.Disposition.DROPPED);

        VerifierRunner clean = new VerifierRunner(new MockVerifier(),
                toolboxReturning("10: value.length()"), VerifyConfig.defaults());
        assertThat(clean.review(request()).disposition())
                .isEqualTo(VerifyResult.Disposition.ASSERTION);
    }
}
