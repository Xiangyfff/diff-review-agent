package com.codereview.agent.model;

import com.codereview.agent.investigate.EvidenceWindow;
import com.codereview.agent.investigate.InvestigationModel;
import com.codereview.agent.investigate.InvestigationState;
import com.codereview.agent.investigate.ModelTurn;
import com.codereview.agent.investigate.Question;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.Toolbox;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One turn of the investigation loop, answered by a real model.
 *
 * <p>The model is given the unit, the questions still open, the evidence ids it already holds and the
 * tool catalogue — and exactly three legal moves. Everything else it might want to do (write prose,
 * speculate, cite a line it never read) is either unrepresentable in the reply shape or rejected
 * downstream by the verifier.
 */
public final class LangChain4jInvestigationModel implements InvestigationModel {

    private static final String SYSTEM = """
            你是代码审查助手中的深挖环节。你只能做三件事，每轮只做一件：调用一个只读工具、
            放弃一个当前无法回答的问题、或者在引用已取得证据的前提下给出一条结论。
            硬性要求：结论必须引用「已取得证据」里的编号；结论所在行必须落在被引证据的行区间内；
            结论里用反引号括起来的符号必须原样出现在被引证据的原文里；结论不超过三百字。
            不许写建议、不许写严重程度、不许引用没有出现过的编号。只输出一个 JSON 对象。
            """;

    private final Chat chat;
    private final Toolbox toolbox;

    public LangChain4jInvestigationModel(Chat chat, Toolbox toolbox) {
        this.chat = chat;
        this.toolbox = toolbox;
    }

    @Override
    public ModelTurn next(InvestigationState state) {
        String reply = ModelCall.ask(chat, SYSTEM, render(state) + "\n\n" + SHAPE);
        return turnFrom(JsonReplies.object(reply));
    }

    static ModelTurn turnFrom(JsonNode node) {
        String action = JsonReplies.text(node, "action");
        String question = JsonReplies.text(node, "question");
        if (question.isEmpty()) {
            throw new IllegalArgumentException("the reply names no question");
        }
        return switch (action) {
            case "tool" -> new ModelTurn.CallTool(question,
                    new ToolCall(JsonReplies.text(node, "tool"), arguments(node.path("arguments"))));
            case "abandon" -> new ModelTurn.Abandon(question, JsonReplies.text(node, "reason"));
            case "conclude" -> new ModelTurn.Conclude(question,
                    JsonReplies.text(node, "statement"),
                    JsonReplies.strings(node, "evidence"),
                    JsonReplies.integer(node, "line", 0));
            default -> throw new IllegalArgumentException("unknown action: " + action);
        };
    }

    private static Map<String, String> arguments(JsonNode node) {
        Map<String, String> arguments = new LinkedHashMap<>();
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                arguments.put(name, node.path(name).asText());
            }
        }
        return arguments;
    }

    private String render(InvestigationState state) {
        StringBuilder text = new StringBuilder();
        text.append("单元：").append(state.unitKey()).append("（文件 ").append(state.file())
                .append("，改动后从第 ").append(state.line()).append(" 行开始）\n\n");
        text.append("--- 改动前 ---\n").append(state.before()).append("\n--- 改动后 ---\n")
                .append(state.after()).append("\n\n");

        if (!state.changes().isEmpty()) {
            text.append("检测到的结构变化：\n");
            state.changes().forEach(change -> text.append("- ").append(change).append('\n'));
            text.append('\n');
        }

        text.append("仍然待回答的问题：\n");
        for (Question question : state.open()) {
            text.append("- ").append(question.id()).append("：").append(question.text()).append('\n');
        }
        text.append('\n');

        text.append("你已取得的证据（结论只能引用这些编号）：\n");
        if (state.evidence().isEmpty()) {
            text.append("- 无\n");
        } else {
            // Older evidence folds to its id and location; the newest few stay verbatim. Nothing is
            // deleted — the checks at conclusion time read the store, not this prompt — but a prompt
            // that grows with every tool call is how a loop like this quietly stops being affordable.
            text.append(EvidenceWindow.render(state.evidence(), java.util.Set.of(),
                    EvidenceWindow.DEFAULT_FULL_LIMIT));
        }
        text.append('\n');

        text.append("可用工具：\n").append(toolbox.render()).append('\n');
        text.append("预算：已调用 ").append(state.toolCallsUsed()).append(" 次，还剩 ")
                .append(state.toolCallsLeft()).append(" 次。\n");
        return text.toString();
    }

    private static final String SHAPE = """
            只输出下面三种之一，不要输出其他任何内容：
            {"action":"tool","question":"q1","tool":"read_lines","arguments":{"path":"路径","start":"10","end":"40"}}
            {"action":"abandon","question":"q1","reason":"为什么现在无法回答"}
            {"action":"conclude","question":"q1","statement":"一句话结论","evidence":["ev-1"],"line":42}
            """;
}
