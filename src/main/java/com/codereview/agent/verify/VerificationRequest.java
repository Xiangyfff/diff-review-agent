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
        String unitAfter) {

    public VerificationRequest {
        cited = cited == null ? List.of() : List.copyOf(cited);
        unitBefore = unitBefore == null ? "" : unitBefore;
        unitAfter = unitAfter == null ? "" : unitAfter;
    }

    public String render() {
        StringBuilder builder = new StringBuilder();
        builder.append("你是一名复核者。下面是一条已经写出来的结论，你的任务是反驳它，而不是同意它。\n");
        builder.append("只有当你找到一个能让这条结论不成立的具体事实（比如上游已经做了非空检查、")
                .append("该分支根本走不到、被调用的方法内部已经做了限制）并且用工具读到了那段代码，")
                .append("才可以撤销它；找不到就维持原结论，不要因为「感觉不确定」而否定它。\n\n");
        builder.append("位置：").append(finding.file()).append(":").append(finding.line()).append('\n');
        builder.append("结论：").append(finding.statement()).append("\n\n");
        builder.append("它引用的证据：\n");
        for (Evidence evidence : cited) {
            builder.append("- ").append(evidence.cite()).append('\n')
                    .append(evidence.text()).append('\n');
        }
        builder.append("\n这个单元改动前的代码：\n").append(unitBefore).append('\n');
        builder.append("这个单元改动后的代码：\n").append(unitAfter).append('\n');
        return builder.toString();
    }
}
