package com.codereview.agent.investigate;

import org.junit.jupiter.api.Test;

import com.codereview.agent.triage.policy.PolicyChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loop's stopping conditions are the part that matters: a run must end for a reason it can
 * name, and a conclusion must only count when the verifier accepts it.
 */
class InvestigationRunnerTest {

    private static final String FILE = "a/Login.java";

    private static final class Scripted implements InvestigationModel {

        private final Deque<ModelTurn> turns;

        Scripted(ModelTurn... turns) {
            this.turns = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public ModelTurn next(InvestigationState state) {
            return turns.isEmpty() ? null : turns.poll();
        }
    }

    private static final class FixedTool implements ReviewTool {

        private final boolean producesText;

        FixedTool(boolean producesText) {
            this.producesText = producesText;
        }

        @Override
        public String name() {
            return "read_lines";
        }

        @Override
        public String purpose() {
            return "test tool";
        }

        @Override
        public String arguments() {
            return "none";
        }

        @Override
        public ToolOutcome run(ToolCall call) {
            if (!producesText) {
                return ToolOutcome.failed("nothing here");
            }
            return ToolOutcome.of("one excerpt", List.of(
                    new ToolOutcome.Excerpt(FILE, 10, 14, "10: if (user == null) {\n11:     return null;")));
        }
    }

    private static InvestigationTask task(String... questions) {
        return new InvestigationTask("u1", FILE, 12, "before", "after", List.of("NULL_CHECK_REMOVED"),
                List.of(questions), "");
    }

    private static ToolCall read() {
        return new ToolCall("read_lines", Map.of("path", FILE, "start", "10", "end", "14"));
    }

    @Test
    void aModelThatGivesUpBeforeReadingIsMadeToReadOnceFirst() {
        // The small local model does exactly this: abandons on turn one with no tool call. The
        // runner turns that into one read of the changed lines and asks again.
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.Abandon("q1", "no need to look"),
                        new ModelTurn.Conclude("q1",
                                "`user` is dereferenced without its guard, so a null caller now fails.",
                                List.of("ev-1"), 12, ModelTurn.Conclude.FIX)),
                new Toolbox(List.of(new FixedTool(true))),
                InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task("Is this value still allowed to be null?"));

        assertThat(result.findings()).hasSize(1);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.attempts()).anyMatch(attempt -> attempt.startsWith("primed "));
        assertThat(result.abandoned()).isEmpty();
    }

    @Test
    void thePrimedReadDoesNotHappenTwiceAndAnAbandonStillClosesTheQuestion() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.Abandon("q1", "no need to look"),
                        new ModelTurn.Abandon("q1", "still no need")),
                new Toolbox(List.of(new FixedTool(true))),
                InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task("Is this value still allowed to be null?"));

        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.attempts()).anyMatch(attempt -> attempt.startsWith("primed "));
        assertThat(result.abandoned()).hasSize(1);
        assertThat(result.abandoned().get(0).reason()).contains("still no need");
    }

    @Test
    void concludesWhenTheModelCitesEvidenceItActuallyRead() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.Conclude("q1", "第 12 行直接返回了 `user` 的空值。", List.of("ev-1"), 12)),
                new Toolbox(List.of(new FixedTool(true))),
                InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task("这个值是否可能为空？"));

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).evidenceIds()).containsExactly("ev-1");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.CONCLUDED);
    }

    @Test
    void stopsAskingForToolsWhenTheToolBudgetIsSpent() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.CallTool("q1", read())),
                new Toolbox(List.of(new FixedTool(true))),
                new InvestigateConfig(2, 12, 2));

        InvestigationResult result = runner.investigate(task("问题"));

        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.TOOL_BUDGET_EXHAUSTED);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void stopsAfterTheTurnBudgetEvenIfTheModelKeepsWorking() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(new ModelTurn.CallTool("q1", read()), new ModelTurn.CallTool("q1", read())),
                new Toolbox(List.of(new FixedTool(true))),
                new InvestigateConfig(8, 1, 2));

        InvestigationResult result = runner.investigate(task("问题"));

        assertThat(result.turns()).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.TURN_BUDGET_EXHAUSTED);
    }

    @Test
    void recordsAnAbandonedQuestionWithItsReason() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.Abandon("q1", "仓库里找不到这个接口的实现")),
                new Toolbox(List.of(new FixedTool(true))),
                InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task("这个实现是谁？"));

        assertThat(result.abandoned()).hasSize(1);
        assertThat(result.abandoned().get(0).reason()).contains("找不到");
        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.CONCLUDED);
    }

    @Test
    void aConclusionThatCitesNothingIsCountedAsRejectedRatherThanReported() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(new ModelTurn.Conclude("q1", "这里有问题。", List.of("ev-7"), 12)),
                new Toolbox(List.of(new FixedTool(true))),
                InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task("问题"));

        assertThat(result.findings()).isEmpty();
        assertThat(result.rejectedFindings()).isEqualTo(1);
    }

    @Test
    void stopsWhenToolsKeepComingBackEmpty() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.CallTool("q1", read())),
                new Toolbox(List.of(new FixedTool(false))),
                InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task("问题"));

        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.STALLED);
        assertThat(result.toolCalls()).isEqualTo(3);
    }

    @Test
    void anUnknownToolCountsAsAFruitlessCall() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.CallTool("q1", new ToolCall("grep_everything", Map.of())),
                        new ModelTurn.CallTool("q1", new ToolCall("grep_everything", Map.of())),
                        new ModelTurn.CallTool("q1", new ToolCall("grep_everything", Map.of()))),
                new Toolbox(List.of(new FixedTool(true))),
                InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task("问题"));

        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.STALLED);
    }

    @Test
    void reportsModelFailureInsteadOfThrowing() {
        InvestigationModel exploding = state -> {
            throw new IllegalStateException("upstream 500");
        };
        InvestigationRunner runner = new InvestigationRunner(exploding,
                new Toolbox(List.of(new FixedTool(true))), InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task("问题"));

        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.MODEL_FAILED);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void handsTheModelTheQuestionsItStillOwesAnAnswerTo() {
        List<InvestigationState> seen = new ArrayList<>();
        InvestigationModel capturing = state -> {
            seen.add(state);
            return new ModelTurn.Abandon("q1", "放弃");
        };
        InvestigationRunner runner = new InvestigationRunner(capturing,
                new Toolbox(List.of(new FixedTool(true))), InvestigateConfig.defaults());

        runner.investigate(task("第一个问题", "第二个问题"));

        assertThat(seen.get(0).open()).hasSize(2);
        assertThat(seen.get(0).toolCallsLeft()).isEqualTo(8);
    }

    @Test
    void anEmptyQueueIsOpenedByTheModelAskingItsOwnQuestions() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.Raise(List.of(
                                new ModelTurn.Seed("after", "这个改动改变了行为吗？", 1))),
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.Reject("q1", "读到的代码在上游已经判空", List.of("ev-1"))),
                new Toolbox(List.of(new FixedTool(true))), InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task());

        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.CONCLUDED);
        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).status())
                .isEqualTo(InvestigationResult.QuestionState.Status.REJECTED);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void aQuestionWhoseTargetIsNotInTheUnitIsNotQueued() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(new ModelTurn.Raise(List.of(
                        new ModelTurn.Seed("MissingSymbol", "它会被为 null 吗？", 1)))),
                new Toolbox(List.of(new FixedTool(true))), InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task());

        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.NOTHING_TO_ASK);
        assertThat(result.questions()).isEmpty();
        assertThat(result.rejectedFindings()).isEqualTo(1);
    }

    @Test
    void aRejectionNeedsEvidenceFromACodeRead() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.Reject("q1", "看起来没问题", List.of()),
                        new ModelTurn.Reject("q1", "读到的代码在上游已经判空", List.of("ev-1"))),
                new Toolbox(List.of(new FixedTool(true))), InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task("空参数会出事吗？"));

        assertThat(result.rejectedFindings()).isEqualTo(1);
        assertThat(result.questions()).singleElement().satisfies(question ->
                assertThat(question.status())
                        .isEqualTo(InvestigationResult.QuestionState.Status.REJECTED));
    }

    @Test
    void theRunnerPicksTheMostUrgentQuestionAndTheModelCannotSkipAhead() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.Raise(List.of(
                                new ModelTurn.Seed("after", "第一个问题", 1),
                                new ModelTurn.Seed("after", "第二个问题", 2))),
                        new ModelTurn.Abandon("q2", "先答第二个"),
                        new ModelTurn.Abandon("q1", "答不了"),
                        new ModelTurn.Abandon("q2", "也答不了")),
                new Toolbox(List.of(new FixedTool(true))), InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task());

        assertThat(result.rejectedFindings()).isEqualTo(1);
        assertThat(result.abandoned()).hasSize(2);
        assertThat(result.questions().get(0).status())
                .isEqualTo(InvestigationResult.QuestionState.Status.ABANDONED);
    }

    @Test
    void aQuestionIsGivenThreeToolCallsAtMost() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.CallTool("q1", read(10)),
                        new ModelTurn.CallTool("q1", read(11)),
                        new ModelTurn.CallTool("q1", read(12)),
                        new ModelTurn.CallTool("q1", read(13)),
                        new ModelTurn.Abandon("q1", "查不动了")),
                new Toolbox(List.of(new FixedTool(true))), InvestigateConfig.defaults());

        InvestigationResult result = runner.investigate(task("这里安全吗？"));

        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(result.rejectedFindings()).isEqualTo(1);
        assertThat(result.abandoned()).hasSize(1);
    }

    private static ToolCall read(int start) {
        return new ToolCall("read_lines", Map.of("path", FILE,
                "start", String.valueOf(start), "end", String.valueOf(start + 4)));
    }

    private static final String CLAUSE_TEXT = "订单查找允许空参数，返回空列表。";

    private static PolicyChunk allowance() {
        return new PolicyChunk("pol-test-1", "docs/policies/x.md", "允许空参数", CLAUSE_TEXT,
                false, false, true, List.of("NULL_CHECK_REMOVED"), List.of("a/**"),
                List.of("return List.of();"));
    }

    @Test
    void anExemptionThatQuotesTheClauseAndCitesTheLineIsRecorded() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.Exempt("q1", "pol-test-1", CLAUSE_TEXT,
                                "读到的代码在空参数时直接返回空列表，符合条款", List.of("ev-1"), 12)),
                new Toolbox(List.of(new FixedTool(true))),
                InvestigateConfig.defaults(), Map.of(), Map.of("pol-test-1", allowance()));

        InvestigationResult result = runner.investigate(task("空参数会出事吗？"));

        assertThat(result.exemptions()).hasSize(1);
        assertThat(result.exemptions().get(0).clauseId()).isEqualTo("pol-test-1");
        assertThat(result.outcome()).isEqualTo(InvestigationResult.Outcome.EXEMPTED);
        assertThat(result.findings()).isEmpty();
        assertThat(result.abandoned()).isEmpty();
    }

    @Test
    void anExemptionThatDoesNotQuoteTheClauseIsRejected() {
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.Exempt("q1", "pol-test-1", "这里大概没问题",
                                "随便说说", List.of("ev-1"), 12),
                        new ModelTurn.Exempt("q1", "pol-test-1", "这里大概没问题",
                                "随便说说", List.of("ev-1"), 12)),
                new Toolbox(List.of(new FixedTool(true))),
                InvestigateConfig.defaults(), Map.of(), Map.of("pol-test-1", allowance()));

        InvestigationResult result = runner.investigate(task("空参数会出事吗？"));

        assertThat(result.exemptions()).isEmpty();
        assertThat(result.rejectedFindings()).isEqualTo(2);
        assertThat(result.abandoned()).hasSize(1);
        assertThat(result.abandoned().get(0).reason()).contains("豁免");
    }

    @Test
    void aClauseThatOnlyForbidsCannotExempt() {
        PolicyChunk forbidding = new PolicyChunk("pol-test-2", "docs/policies/x.md", "空参数",
                CLAUSE_TEXT, true);
        InvestigationRunner runner = new InvestigationRunner(
                new Scripted(
                        new ModelTurn.CallTool("q1", read()),
                        new ModelTurn.Exempt("q1", "pol-test-2", CLAUSE_TEXT,
                                "说这是允许的", List.of("ev-1"), 12)),
                new Toolbox(List.of(new FixedTool(true))),
                InvestigateConfig.defaults(), Map.of(), Map.of("pol-test-2", forbidding));

        InvestigationResult result = runner.investigate(task("空参数会出事吗？"));

        assertThat(result.exemptions()).isEmpty();
        assertThat(result.rejectedFindings()).isEqualTo(1);
    }
}
