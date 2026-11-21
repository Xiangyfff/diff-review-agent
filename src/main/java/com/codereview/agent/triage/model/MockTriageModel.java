package com.codereview.agent.triage.model;

import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.tier.Tier;

import java.util.ArrayList;
import java.util.List;

/**
 * An offline stand-in for the language model.
 *
 * <p>Every question is a template over a detected shape, so the whole pipeline can be built,
 * tested and demonstrated with no network, no key and no cost. That matters more than it
 * looks: the evaluation harness has to run hundreds of cases in CI, and a suite that calls a
 * real model is not a test suite, it is a bill.
 *
 * <p>The mock is deliberately not smart. It cannot read code, so it produces exactly the
 * questions the shape itself implies, which is the floor the real model has to beat.
 */
public final class MockTriageModel implements TriageModel {

    private static final int MAX_QUESTIONS = 3;

    @Override
    public UnitAssessment assess(UnitPrompt prompt) {
        List<FindingQuestion> questions = new ArrayList<>();
        for (StructuralChange change : prompt.changes()) {
            String question = questionFor(change, prompt);
            if (question == null) {
                continue;
            }
            FindingQuestion candidate = new FindingQuestion(targetOf(change, prompt.unitKey()), question);
            if (candidate.isWellFormed() && questions.stream().noneMatch(q -> q.text().equals(question))) {
                questions.add(candidate);
            }
            if (questions.size() >= MAX_QUESTIONS) {
                break;
            }
        }
        boolean worthIt = prompt.tier() == Tier.MUST_INVESTIGATE || !questions.isEmpty();
        String abstain = questions.isEmpty() ? "检测到的变化没有可以具体验证的问题" : "";
        return new UnitAssessment(questions, worthIt, abstain, "由结构变化模板生成");
    }

    @Override
    public IntentSummary summarize(ChangeOverview overview) {
        List<String> concerns = new ArrayList<>();
        for (ChangeOverview.FileSummary file : overview.files()) {
            if (!file.removedDeclarations().isEmpty()) {
                concerns.add(file.path() + " 删除了 " + String.join("、", file.removedDeclarations())
                        + "，需要确认没有调用方仍在引用");
            }
        }
        String intent = overview.files().isEmpty()
                ? "本次改动没有可审查的单元"
                : "本次改动涉及 " + overview.files().size() + " 个文件、"
                + overview.unitCount() + " 个分析单元，共增加 " + overview.addedLines()
                + " 行、删除 " + overview.removedLines() + " 行";
        return new IntentSummary(intent, concerns);
    }

    private static String questionFor(StructuralChange change, UnitPrompt prompt) {
        return switch (change.kind()) {
            case NULL_CHECK_REMOVED -> "这个值在当前位置是否仍可能为空？";
            case SIGNATURE_CHANGED -> "所有调用方是否已经按新签名更新？";
            case VISIBILITY_CHANGED -> "这次可见性变化是否破坏了某个调用方的预期？";
            case THROWS_CHANGED -> "调用链上是否有人处理这个异常的变化？";
            case SWALLOWED_EXCEPTION -> "异常被吞掉之后，失败会以什么方式暴露出来？";
            case RESOURCE_UNCLOSED -> "这个资源在后续代码里是否一定被关闭？";
            case CONDITION_INVERTED -> "条件翻转后在哪些输入上行为与改动前不同？";
            case MODIFIER_CHANGED -> "修饰符变化之后，并发可见性或初始化顺序是否仍然成立？";
            case UNIT_ADDED -> prompt.file().contains("src/test/")
                    ? "这个新增的测试覆盖了哪条行为变更？"
                    : "新增的入口是否已有调用方和对应的测试？";
            case UNIT_REMOVED -> "删除之后是否还有代码引用它？";
            case ANNOTATION_REMOVED -> "被移除的注解原本提供的横切行为现在由谁承担？";
            case ANNOTATION_ADDED -> "新增的注解是否会改变这个方法的契约或运行期行为？";
            case EARLY_EXIT_REMOVED -> "去掉这个提前返回后，所有路径的返回值是否仍然正确？";
            case TYPE_LEVEL_CHANGED -> "导入或类级注解的变化是否改变了这个类的契约？";
            case STATEMENTS_DELETED -> "被删除的语句是否承担了某个副作用？";
            case STATEMENTS_ADDED -> "新增的语句在什么条件下会执行？";
            case OPERATOR_CHANGED -> "运算符改动之后，哪些输入的判断结果会与改动前不同？";
            case STATEMENTS_REORDERED -> "语句换序之后，副作用发生的顺序是否会改变结果？";
            case UNPARSED -> null;
        };
    }

    private static String targetOf(StructuralChange change, String unitKey) {
        return FindingQuestion.symbolIn(change.detail(), unitKey);
    }
}
