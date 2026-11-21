package com.codereview.agent.model;

import com.codereview.agent.investigate.ModelTurn;
import com.codereview.agent.triage.model.UnitAssessment;
import com.codereview.agent.verify.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelAdaptersTest {

    @Test
    void parsesATriageAssessment() {
        String reply = """
                {"questions":[{"target":"`total`","text":"它可能为空吗？"}],
                 "worthInvestigating":true,"abstainReason":"","rationale":"删了判空"}
                """;
        UnitAssessment assessment = LangChain4jTriageModel.assessmentFrom(JsonReplies.object(reply));
        assertThat(assessment.questions()).hasSize(1);
        assertThat(assessment.questions().get(0).target()).isEqualTo("`total`");
        assertThat(assessment.worthInvestigating()).isTrue();
    }

    @Test
    void defaultsWorthInvestigatingToWhetherQuestionsExist() {
        UnitAssessment empty = LangChain4jTriageModel.assessmentFrom(
                JsonReplies.object("{\"questions\":[]}"));
        assertThat(empty.worthInvestigating()).isFalse();

        UnitAssessment some = LangChain4jTriageModel.assessmentFrom(
                JsonReplies.object("{\"questions\":[{\"target\":\"a\",\"text\":\"b\"}]}"));
        assertThat(some.worthInvestigating()).isTrue();
    }

    @Test
    void parsesAToolCallTurn() {
        String reply = """
                {"action":"tool","question":"q1","tool":"read_lines",
                 "arguments":{"path":"src/A.java","start":"10","end":"20"}}
                """;
        ModelTurn turn = LangChain4jInvestigationModel.turnFrom(JsonReplies.object(reply));
        assertThat(turn).isInstanceOf(ModelTurn.CallTool.class);
        ModelTurn.CallTool call = (ModelTurn.CallTool) turn;
        assertThat(call.call().tool()).isEqualTo("read_lines");
        assertThat(call.call().intArgument("start", -1)).isEqualTo(10);
    }

    @Test
    void parsesAConcludeTurn() {
        String reply = """
                {"action":"conclude","question":"q2","statement":"`total` 可能为空。",
                 "evidence":["ev-1","ev-2"],"line":42}
                """;
        ModelTurn turn = LangChain4jInvestigationModel.turnFrom(JsonReplies.object(reply));
        assertThat(turn).isInstanceOf(ModelTurn.Conclude.class);
        ModelTurn.Conclude conclude = (ModelTurn.Conclude) turn;
        assertThat(conclude.evidenceIds()).containsExactly("ev-1", "ev-2");
        assertThat(conclude.line()).isEqualTo(42);
    }

    @Test
    void parsesAnAbandonTurn() {
        ModelTurn turn = LangChain4jInvestigationModel.turnFrom(JsonReplies.object(
                "{\"action\":\"abandon\",\"question\":\"q3\",\"reason\":\"没有调用方\"}"));
        assertThat(turn).isInstanceOf(ModelTurn.Abandon.class);
    }

    @Test
    void refusesAnUnknownAction() {
        assertThatThrownBy(() -> LangChain4jInvestigationModel.turnFrom(JsonReplies.object(
                "{\"action\":\"rewrite\",\"question\":\"q1\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown action");
    }

    @Test
    void refusesATurnThatNamesNoQuestion() {
        assertThatThrownBy(() -> LangChain4jInvestigationModel.turnFrom(JsonReplies.object(
                "{\"action\":\"tool\",\"tool\":\"read_lines\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no question");
    }

    @Test
    void parsesAllThreeVerdicts() {
        assertThat(LangChain4jVerifier.verdict(JsonReplies.object(
                "{\"action\":\"keep\",\"reason\":\"确实为空\"}"), "keep"))
                .isInstanceOf(Verdict.Keep.class);

        Verdict retract = LangChain4jVerifier.verdict(JsonReplies.object(
                "{\"action\":\"retract\",\"reason\":\"上游已判空\",\"evidence\":[\"ev-1\"]}"), "retract");
        assertThat(retract).isInstanceOf(Verdict.Retract.class);
        assertThat(((Verdict.Retract) retract).counterEvidenceIds()).containsExactly("ev-1");

        Verdict downgrade = LangChain4jVerifier.verdict(JsonReplies.object(
                "{\"action\":\"downgrade\",\"reason\":\"只在并发下成立\",\"evidence\":[\"ev-2\"]}"),
                "downgrade");
        assertThat(downgrade).isInstanceOf(Verdict.Downgrade.class);
    }

    @Test
    void theInvestigationPromptFoldsOldEvidenceInsteadOfRepeatingIt() {
        List<com.codereview.agent.investigate.Evidence> evidence = new ArrayList<>();
        int naive = 0;
        for (int index = 1; index <= 10; index++) {
            StringBuilder text = new StringBuilder("line " + index + " of file " + index + "\n");
            for (int row = 0; row < 8; row++) {
                text.append("    statement number ").append(row).append(" in unit ").append(index)
                        .append("\n");
            }
            naive += text.length();
            evidence.add(new com.codereview.agent.investigate.Evidence("ev-" + index, "read_lines",
                    "src/F" + index + ".java", 1, 9, text.toString()));
        }
        var state = new com.codereview.agent.investigate.InvestigationState("METHOD#X.y()",
                "src/X.java", 10, "void y() {}", "void y() { }", List.of("statement added"),
                List.of(new com.codereview.agent.investigate.Question("q1", "这个值可能为空吗？")),
                evidence, 3, 5, "", java.util.List.of(), "");

        List<String> prompts = new ArrayList<>();
        Chat chat = (system, user) -> {
            prompts.add(user);
            return "{\"action\":\"conclude\",\"question\":\"q1\",\"statement\":\"`y` 需要确认\","
                    + "\"evidence\":[\"ev-10\"],\"line\":12}";
        };

        ModelTurn turn = new LangChain4jInvestigationModel(chat,
                new com.codereview.agent.investigate.Toolbox(List.of())).next(state);

        assertThat(turn).isInstanceOf(ModelTurn.Conclude.class);
        assertThat(prompts).hasSize(1);
        String prompt = prompts.get(0);
        assertThat(prompt).contains("已折叠");
        assertThat(prompt.length()).isLessThan(naive);
        assertThat(prompt.length()).isLessThan((int) (naive * 0.9));
    }

    @Test
    void asksAgainOnceWhenTheFirstReplyIsNotJson() {
        List<String> prompts = new ArrayList<>();
        Chat chat = (system, user) -> {
            prompts.add(user);
            return prompts.size() == 1 ? "我觉得这段代码有问题" : "{\"ok\":true}";
        };
        String reply = ModelCall.ask(chat, "system", "原始请求");
        assertThat(reply).contains("ok");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("不是合法的 JSON");
    }

    @Test
    void surfacesTheSecondFailureInsteadOfUsingIt() {
        Chat chat = (system, user) -> "still not json";
        assertThatThrownBy(() -> ModelCall.ask(chat, "system", "原始请求"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
