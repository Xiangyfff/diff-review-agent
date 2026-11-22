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
            一条结论只有在你能说清「哪里有问题、作者要改什么或确认什么」时才成立。
            只复述这次改了什么（比如「签名从 A 变成了 B」「注解没变」）不是结论，那种情况请改用 abandon，
            理由写「检查后确认这里没有问题：…」。如果查完发现确实没毛病，也必须 abandon，不要为了交差硬凑结论。
            但「查完」的意思是至少读过改动所在的代码：还没有调用过任何工具就放弃，等于没查，
            这种情况下请先用 read_lines 读改动前后的方法，再决定。改动涉及删除或削弱防御性检查时，
            除非真的读到了明确保证（上游判空、非空注解、构造器校验），否则不要放弃，改为用 confirm 让作者确认。
            唯一的例外是「仓库里找不到调用方」：先用 public_surface 看这个声明对外是否可达。如果它是 public，
            那么仓库外的调用方本来就看不见，不能据此放弃——改用 confirm 请作者确认外部调用方是否已同步，
            或者用 fix 指出需要一起改的地方；只有当它确实不可从外部调用时，空的调用列表才算「没人用」。
            硬性要求：结论必须引用「已取得证据」里的编号；结论所在行必须落在被引证据的行区间内；
            结论里用反引号括起来的符号必须原样出现在被引证据的原文里；结论不超过三百字。
            不许猜严重程度、不许引用没有出现过的编号。只输出一个 JSON 对象。
            """;

    private final Chat chat;
    private final Toolbox toolbox;

    public LangChain4jInvestigationModel(Chat chat, Toolbox toolbox) {
        this.chat = chat;
        this.toolbox = toolbox;
    }

    private static int unitEndLine(InvestigationState state) {
        String after = state.after() == null ? "" : state.after();
        long lines = after.isEmpty() ? 0 : after.lines().count();
        return (int) (state.line() + Math.max(0, lines - 1));
    }

    @Override
    public ModelTurn next(InvestigationState state) {
        String reply = ModelCall.ask(chat, SYSTEM, render(state) + "\n\n" + SHAPE,
                "emit_turn", "调查单元时的下一步动作：调工具、放弃并说明理由，或下一条带证据的结论",
                Schemas.INVESTIGATION_TURN);
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
                    JsonReplies.integer(node, "line", 0),
                    JsonReplies.text(node, "demand"));
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
        text.append("\n这段代码在本文件里从第 ").append(state.line()).append(" 行到第 ")
                .append(unitEndLine(state)).append(" 行。\n");
        text.append("结论里的 line 必须落在这个行范围内，或者落在你所引证据覆盖的行区间内；"
                + "两者都不满足的结论会被机械校验拒绝。\n");
        text.append("单元：").append(state.unitKey()).append("（文件 ").append(state.file())
                .append("，改动后从第 ").append(state.line()).append(" 行开始）\n\n");
        text.append("--- 改动前 ---\n").append(state.before()).append("\n--- 改动后 ---\n")
                .append(state.after()).append("\n\n");

        if (!state.facts().isEmpty()) {
            text.append("初审给出的背景事实（按名字统计，只是线索，不能当成结论）：\n")
                    .append(state.facts()).append("\n\n");
        }

        if (!state.changes().isEmpty()) {
            text.append("检测到的结构变化：\n");
            state.changes().forEach(change -> text.append("- ").append(change).append('\n'));
            text.append('\n');
        }

        if (!state.attempts().isEmpty()) {
            text.append("已经做过的工具调用（包括什么都没查到的）：\n");
            for (String attempt : state.attempts()) {
                text.append("- ").append(attempt).append('\n');
            }
            text.append('\n');
        }

        if (!state.lastRejection().isEmpty()) {
            text.append("你上一次给出的结论没有通过证据校验，原因是：")
                    .append(state.lastRejection())
                    .append("\n请先补上缺失的证据再下结论，不要原样重复上一次的说法。\n\n");
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
            {"action":"tool","question":"q1","tool":"read_lines","arguments":{"path":"<仓库内相对路径>","start":"<起始行>","end":"<结束行>"}}
            {"action":"abandon","question":"q1","reason":"<查了什么、为什么还是回答不了>"}
            {"action":"conclude","question":"q1","statement":"<一句话说清问题和要改什么>","demand":"fix","evidence":["ev-1"],"line":42}

            尖括号里是说明，不是要照抄的文字：每一处都要换成你自己写的内容。

            demand 只能是 fix 或 confirm：改代码能解决就用 fix（例如「这里少了一个空判，`order` 可能为 null」）；
            必须由作者判断、代码本身不一定是错的用 confirm（例如「这个重载被外部模块调用，请确认调用方已同步」）。
            写不出一句能让作者动手的话，就说明这条不该下结论，改用 abandon。
            """;
}
