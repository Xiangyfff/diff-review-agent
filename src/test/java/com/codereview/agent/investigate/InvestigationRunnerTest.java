package com.codereview.agent.investigate;

import org.junit.jupiter.api.Test;

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
                new Scripted(new ModelTurn.Abandon("q1", "仓库里找不到这个接口的实现")),
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
}
