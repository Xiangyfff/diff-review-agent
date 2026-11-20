package com.codereview.agent.model;

import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;
import com.codereview.agent.verify.ToolSession;
import com.codereview.agent.verify.VerificationRequest;
import com.codereview.agent.verify.Verdict;
import com.codereview.agent.verify.Verifier;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The critic, answered by a real model.
 *
 * <p>The model drives the tool budget itself: it may look before it decides, which is the whole point
 * of asking it to refute rather than to agree. When the budget is gone it has to answer with what it
 * has, and a retraction still needs a citation the session can resolve — the runner enforces that, not
 * this class.
 */
public final class LangChain4jVerifier implements Verifier {

    private static final String SYSTEM = """
            你是代码审查助手的复核环节。你的任务不是同意，而是反驳上一条结论。
            只有当你用工具读到了具体事实——上游已经做了非空检查、这个分支走不到、被调方法自己也做了限制——
            才可以撤销结论；只说「我不确定」不足以撤销，请维持。撤销或降级必须给出证据编号。
            只输出一个 JSON 对象，三种形状之一：
            {"action":"tool","tool":"read_lines","arguments":{"path":"路径","start":"10","end":"40"}}
            {"action":"keep","reason":"为什么这条结论成立"}
            {"action":"retract","reason":"为什么它不成立","evidence":["ev-1"]}
            {"action":"downgrade","reason":"哪一部分还需要确认","evidence":["ev-1"]}
            """;

    private static final int MAX_TURNS = 6;

    private final Chat chat;

    public LangChain4jVerifier(Chat chat) {
        this.chat = chat;
    }

    @Override
    public Verdict review(VerificationRequest request, ToolSession session) {
        StringBuilder transcript = new StringBuilder();
        for (int turn = 0; turn < MAX_TURNS; turn++) {
            String reply = ModelCall.ask(chat, SYSTEM,
                    request.render() + transcript + "\n本轮剩余工具调用次数：" + session.callsLeft());
            JsonNode node = JsonReplies.object(reply);
            String action = JsonReplies.text(node, "action");
            if ("tool".equals(action)) {
                if (session.callsLeft() <= 0) {
                    return new Verdict.Keep("复核者的工具预算已经用完，无法找到反证");
                }
                ToolOutcome outcome = session.run(new ToolCall(JsonReplies.text(node, "tool"),
                        arguments(node.path("arguments"))));
                transcript.append("\n工具 ").append(JsonReplies.text(node, "tool")).append(" 返回：")
                        .append(outcome.ok() ? "成功" : "失败").append("；")
                        .append(outcome.summary()).append('\n')
                        .append("已取得证据编号：");
                session.evidence().forEach(evidence -> transcript.append(evidence.id()).append(' '));
                continue;
            }
            return verdict(node, action);
        }
        return new Verdict.Keep("复核者在回合上限内没有给出裁决，按维持处理");
    }

    static Verdict verdict(JsonNode node, String action) {
        String reason = JsonReplies.text(node, "reason");
        return switch (action) {
            case "keep" -> new Verdict.Keep(reason.isEmpty() ? "复核者没有给出反驳理由" : reason);
            case "retract" -> new Verdict.Retract(reason, JsonReplies.strings(node, "evidence"));
            case "downgrade" -> new Verdict.Downgrade(reason, JsonReplies.strings(node, "evidence"));
            default -> throw new IllegalArgumentException("unknown verdict: " + action);
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
}
