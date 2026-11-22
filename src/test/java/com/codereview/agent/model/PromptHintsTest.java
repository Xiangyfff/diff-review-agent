package com.codereview.agent.model;

import com.codereview.agent.investigate.Evidence;
import com.codereview.agent.investigate.Finding;
import com.codereview.agent.investigate.InvestigationState;
import com.codereview.agent.investigate.ModelTurn;
import com.codereview.agent.investigate.Question;
import com.codereview.agent.investigate.Toolbox;
import com.codereview.agent.triage.model.AuthorIntent;
import com.codereview.agent.triage.model.ChangeOverview;
import com.codereview.agent.verify.VerificationRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two hand-offs that are easy to lose: triage's name-level facts must reach the investigator,
 * and the investigator's own attempts must reach the critic so it does not repeat them.
 */
class PromptHintsTest {

    @Test
    void triageFactsReachTheInvestigationPrompt() {
        List<String> prompts = new ArrayList<>();
        Chat chat = (system, user) -> {
            prompts.add(user);
            return "{\"action\":\"abandon\",\"question\":\"q1\",\"reason\":\"证据已经足够\"}";
        };
        InvestigationState state = new InvestigationState("METHOD#OrderService.find(int)",
                "src/OrderService.java", 20, "int find(int id) { return 1; }",
                "int find(int id) { return 2; }", List.of("statement added"),
                List.of(new Question("q1", "调用方是否依赖返回值的具体数字？")),
                List.of(new Evidence("ev-1", "read_lines", "src/OrderService.java", 1, 3, "1: x")),
                0, 8, "", List.of(), "符号 find 在另外 3 个源文件里出现过（共 4 个文件）。");

        ModelTurn turn = new LangChain4jInvestigationModel(chat, new Toolbox(List.of())).next(state);

        assertThat(turn).isInstanceOf(ModelTurn.Abandon.class);
        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("背景事实").contains("另外 3 个源文件");
    }

    @Test
    void investigationAttemptsReachTheVerificationPrompt() {
        VerificationRequest request = new VerificationRequest(
                new Finding("METHOD#a.b()", "src/A.java", 12, "`b` 没有校验入参", List.of("ev-1")),
                List.of(new Evidence("ev-1", "read_lines", "src/A.java", 10, 12, "10: void b()")),
                "void b() {}", "void b() { }",
                List.of("find_definition b -> no excerpt (not declared in the index)"));

        String prompt = request.render();

        assertThat(prompt).contains("已经做过的工具调用").contains("no excerpt");
    }

    @Test
    void theAuthorDescriptionReachesTheIntentScanAsAClaim() {
        List<String> prompts = new ArrayList<>();
        Chat chat = (system, user) -> {
            prompts.add(user);
            return "{\"intent\":\"fix a null guard\",\"crossFileConcerns\":[]}";
        };
        ChangeOverview overview = new ChangeOverview(
                List.of(new ChangeOverview.FileSummary("src/A.java", 2, 3,
                        List.of("A.find"), List.of())),
                2, 3, 1,
                new AuthorIntent("修空指针", "只改了 A.find 的判空", "owner/name#7"));

        new LangChain4jTriageModel(chat).summarize(overview);

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("作者对这次改动的说明")
                .contains("不是证据")
                .contains("只改了 A.find 的判空");
    }

    @Test
    void anAuthorDescriptionOfOnlyWhitespaceAddsNothingToThePrompt() {
        List<String> prompts = new ArrayList<>();
        Chat chat = (system, user) -> {
            prompts.add(user);
            return "{\"intent\":\"x\",\"crossFileConcerns\":[]}";
        };
        ChangeOverview overview = new ChangeOverview(
                List.of(new ChangeOverview.FileSummary("src/A.java", 1, 0, List.of("A.find"), List.of())),
                1, 0, 1,
                AuthorIntent.fromText("   \n  \n", "file"));

        new LangChain4jTriageModel(chat).summarize(overview);

        assertThat(prompts.get(0)).doesNotContain("作者对这次改动的说明");
    }
}
