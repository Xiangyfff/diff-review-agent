package com.codereview.agent.verify;

import com.codereview.agent.investigate.Evidence;
import com.codereview.agent.investigate.Finding;

import java.util.List;

/**
 * Everything the critic gets to see about one finding: the claim, the evidence it already cites,
 * and the unit it belongs to. The critic may look elsewhere, but only through the tool session it
 * is handed, and only against the same budget as the investigator.
 */
public record VerificationRequest(Finding finding, List<Evidence> cited, String unitBefore,
        String unitAfter,
        /** One line per tool call the investigator already made; empty when unknown. */
        List<String> attempts,
        /** Non-empty when the claim is "this clause allows the change": the clause and its quote. */
        String clauseId, String clauseText, String quote, String reason) {

    public VerificationRequest {
        cited = cited == null ? List.of() : List.copyOf(cited);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        unitBefore = unitBefore == null ? "" : unitBefore;
        unitAfter = unitAfter == null ? "" : unitAfter;
        clauseId = clauseId == null ? "" : clauseId;
        clauseText = clauseText == null ? "" : clauseText;
        quote = quote == null ? "" : quote;
        reason = reason == null ? "" : reason;
    }

    /** Kept for callers that only verify findings. */
    public VerificationRequest(Finding finding, List<Evidence> cited, String unitBefore,
            String unitAfter, List<String> attempts) {
        this(finding, cited, unitBefore, unitAfter, attempts, "", "", "", "");
    }

    public boolean exemption() {
        return !clauseId.isEmpty();
    }

    public String render() {
        StringBuilder builder = new StringBuilder();
        builder.append("你是一名复核者。下面是一条已经写出来的结论，你的任务是反驳它，而不是同意它。\n");
        builder.append("只有当你找到一个能让这条结论不成立的具体事实（比如上游已经做了非空检查、")
                .append("该分支根本走不到、被调用的方法内部已经做了限制）并且用工具读到了那段代码，")
                .append("才可以撤销它；找不到就维持原结论，不要因为「感觉不确定」而否定它。\n\n");
        builder.append("位置：").append(finding.file()).append(":").append(finding.line()).append('\n');
        builder.append("结论：").append(finding.statement()).append("\n\n");
        if (exemption()) {
            builder.append("这是一条条款豁免：调查员认为条款 ").append(clauseId)
                    .append(" 明确允许这处改动，而不是有问题。你要验证的是：条款原文是否真的允许；")
                    .append("代码是否真的满足条款写的规则、范围和示例（可以去读调用方、其他文件）；")
                    .append("如果条件并不成立，撤销它并给出反证证据。\n");
            builder.append("条款原文：\n").append(clauseText).append('\n');
            builder.append("调查员引用的一句：").append(quote).append('\n');
            builder.append("理由：").append(reason).append("\n\n");
        }
        builder.append("它引用的证据：\n");
        for (Evidence evidence : cited) {
            builder.append("- ").append(evidence.cite()).append('\n')
                    .append(evidence.text()).append('\n');
        }
        builder.append("\n这个单元改动前的代码：\n").append(unitBefore).append('\n');
        builder.append("这个单元改动后的代码：\n").append(unitAfter).append('\n');
        if (!attempts.isEmpty()) {
            builder.append("\n调查这条结论时已经做过的工具调用（包括什么都没查到的）：\n");
            for (String attempt : attempts) {
                builder.append("- ").append(attempt).append('\n');
            }
            builder.append("不要重复这些调用；要么换个角度找反证，要么直接给出裁决。\n");
        }
        return builder.toString();
    }
}
