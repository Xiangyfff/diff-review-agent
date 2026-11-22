package com.codereview.agent.model;

import com.codereview.agent.triage.model.ChangeOverview;
import com.codereview.agent.triage.model.FindingQuestion;
import com.codereview.agent.triage.model.IntentSummary;
import com.codereview.agent.triage.model.TriageModel;
import com.codereview.agent.triage.model.UnitAssessment;
import com.codereview.agent.triage.model.UnitPrompt;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The triage stage, answered by a real model.
 *
 * <p>Two calls per run: one per unit that needs a judgement, and one over the whole change. The
 * contract is the same one the mock honours, so every number the stage produces stays comparable
 * between the offline stand-in and a real model.
 */
public final class LangChain4jTriageModel implements TriageModel {

    private static final String DESCRIPTION =
            "初审对一个改动单元的判断：待验证的问题、是否值得深挖、不查时的理由";

    private static final String SUMMARY_DESCRIPTION = "对整次改动的概括与跨文件疑点";

    private static final String SYSTEM = """
            你是代码审查助手的初审环节。你的工作不是下结论，而是提出具体、可被工具回答的问题。
            规则：问题必须指向输入里出现过的具体符号或位置；每条只问一件事；不评论输入里没有的代码；
            不猜严重程度；不给修改建议。你只能输出一个 JSON 对象。
            """;

    private static final String ASSESS_SHAPE = """
            只输出这一个 JSON 对象，不要输出其他任何内容：
            {"questions":[{"target":"问题指向的符号或位置","text":"一句话问题"}],
             "worthInvestigating":true,
             "abstainReason":"若你认为不值得查，写一句理由；否则为空字符串",
             "rationale":"一句话说明你怎么挑的问题"}
            """;

    private static final String SUMMARY_SHAPE = """
            只输出这一个 JSON 对象，不要输出其他任何内容：
            {"intent":"一句话说明这次改动想做什么","crossFileConcerns":["跨文件的疑点，没有就给空数组"]}
            """;

    private final Chat chat;

    public LangChain4jTriageModel(Chat chat) {
        this.chat = chat;
    }

    @Override
    public UnitAssessment assess(UnitPrompt prompt) {
        String reply = ModelCall.ask(chat, SYSTEM, prompt.render() + "\n\n" + ASSESS_SHAPE,
                "emit_assessment", DESCRIPTION, Schemas.TRIAGE_ASSESSMENT);
        return assessmentFrom(JsonReplies.object(reply));
    }

    @Override
    public IntentSummary summarize(ChangeOverview overview) {
        String reply = ModelCall.ask(chat, SYSTEM, render(overview) + "\n\n" + SUMMARY_SHAPE,
                "emit_summary", SUMMARY_DESCRIPTION, Schemas.TRIAGE_SUMMARY);
        JsonNode node = JsonReplies.object(reply);
        return new IntentSummary(JsonReplies.text(node, "intent"),
                JsonReplies.strings(node, "crossFileConcerns"));
    }

    static UnitAssessment assessmentFrom(JsonNode node) {
        List<FindingQuestion> questions = new ArrayList<>();
        JsonNode array = node.path("questions");
        if (array.isArray()) {
            for (JsonNode item : array) {
                questions.add(new FindingQuestion(JsonReplies.text(item, "target"),
                        JsonReplies.text(item, "text")));
            }
        }
        boolean worth = JsonReplies.bool(node, "worthInvestigating", !questions.isEmpty());
        return new UnitAssessment(questions, worth, JsonReplies.text(node, "abstainReason"),
                JsonReplies.text(node, "rationale"));
    }

    private static String render(ChangeOverview overview) {
        StringBuilder text = new StringBuilder();
        if (!overview.authorIntent().isEmpty()) {
            text.append(overview.authorIntent().describe()).append("\n")
                    .append("如果改动和这段说明不一致，把不一致写进 crossFileConcerns；")
                    .append("不要因为说明里提到某件事就认为代码真的做了。\n\n");
        }
        text.append("本次改动共 ").append(overview.files().size()).append(" 个文件，")
                .append(overview.unitCount()).append(" 个分析单元，增加 ")
                .append(overview.addedLines()).append(" 行、删除 ")
                .append(overview.removedLines()).append(" 行。\n\n");
        for (ChangeOverview.FileSummary file : overview.files()) {
            text.append("- ").append(file.path())
                    .append("：增加 ").append(file.addedLines())
                    .append(" 行、删除 ").append(file.removedLines()).append(" 行");
            if (!file.changedUnits().isEmpty()) {
                text.append("；改动的声明：").append(String.join("、", file.changedUnits()));
            }
            if (!file.removedDeclarations().isEmpty()) {
                text.append("；删除的声明：").append(String.join("、", file.removedDeclarations()));
            }
            text.append('\n');
        }
        return text.toString();
    }
}
