package com.codereview.agent.triage.model;

import com.codereview.agent.triage.rules.RiskCategory;
import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.tier.Tier;
import com.codereview.agent.triage.unit.AnalysisUnit;

import java.util.List;

/**
 * The complete input of one triage model call.
 *
 * <p>Model calls take a structured record rather than a chat history: the triage stage has
 * no conversation to carry, and keeping the call a pure function of (unit, changes, tier)
 * is what makes it cacheable and replayable.
 */
public record UnitPrompt(
        String unitKey,
        String file,
        int line,
        Tier tier,
        RiskCategory category,
        List<StructuralChange> changes,
        String before,
        String after) {

    public UnitPrompt {
        changes = List.copyOf(changes);
    }

    public static UnitPrompt of(AnalysisUnit unit, Tier tier, RiskCategory category, List<StructuralChange> changes) {
        return new UnitPrompt(unit.key(), unit.file(), unit.startLine(), tier, category,
                changes, unit.textBefore(), unit.textAfter());
    }

    /**
     * Renders the prompt a real model would receive. Keeping it here rather than in the
     * model implementation means the same text is used for logging, for the offline mock and
     * for the future language-model client.
     */
    public String render() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你在做代码审查的初审。下面是一个改动的分析单元，以及程序从语法树里比对出来的结构变化。\n\n");
        prompt.append("文件：").append(file).append("，起始行：").append(line).append('\n');
        prompt.append("单元：").append(unitKey).append('\n');
        prompt.append("风险类别：").append(category).append("（类别只用于排序，不是严重度）\n\n");

        prompt.append("程序检测到的结构变化：\n");
        if (changes.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (StructuralChange change : changes) {
                prompt.append("- ").append(change.describe()).append('\n');
            }
        }

        prompt.append("\n改动前：\n").append(block(before));
        prompt.append("\n改动后：\n").append(block(after));

        prompt.append('\n');
        if (tier == Tier.MUST_INVESTIGATE) {
            prompt.append("这个单元一定会被深挖，你不需要判断值不值得查。\n");
            prompt.append("请只回答一件事：深挖时应该验证哪些具体问题。\n");
        } else {
            prompt.append("请回答两件事：这个单元值不值得深挖；如果值得，深挖时要验证哪些具体问题。\n");
            prompt.append("如果你认为不值得，请写一句「不查的理由」。\n");
        }
        prompt.append("每个问题必须指名一个具体的符号或位置，只能问一件事，并且能被读代码或跑测试回答。\n");
        prompt.append("不要下结论，不要猜严重程度，不要评论没有出现在上面的代码。\n");
        return prompt.toString();
    }

    private static String block(String text) {
        return text == null || text.isBlank() ? "（无）" : text;
    }
}
