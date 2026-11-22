package com.codereview.agent.triage.policy;

import com.codereview.agent.triage.structure.StructuralChange;
import com.codereview.agent.triage.unit.AnalysisUnit;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the sentence that is embedded to look up policy.
 *
 * <p>Not the diff: a whole diff dilutes into "Java code changed" and matches everything a little.
 * The query is the few things a rulebook author would key on - where the change is, what the method
 * is called, and what actually changed - which keeps the two vectors comparable.
 *
 * <p>The shape is described in Chinese. The clauses are written in the language their authors use,
 * and a mixed-language query cost us measurable accuracy: on the same three test cases, describing
 * the change in Chinese raised the right clause's score from 0.49 to 0.61 and left an unrelated
 * change at 0.52, below the cut.
 *
 * <p>The changed code is appended, truncated. A clause is often keyed on an identifier
 * ("System.out", "equalsIgnoreCase") rather than on a topic, and a query that only describes the
 * shape never mentions it, so the exact-term bonus has nothing to match.
 */
public final class PolicyQuery {

    private static final int MAX_DETAIL_CHARS = 400;
    private static final int MAX_CODE_CHARS = 240;

    private PolicyQuery() {
    }

    public static String of(AnalysisUnit unit, List<StructuralChange> changes) {
        String details = changes.stream()
                .map(change -> describe(change.kind()))
                .distinct()
                .collect(Collectors.joining("、"));
        if (details.length() > MAX_DETAIL_CHARS) {
            details = details.substring(0, MAX_DETAIL_CHARS);
        }
        String query = "文件 " + unit.file() + "；改动的方法是 " + unit.name()
                + "；结构变化：" + details;
        String code = unit.after() == null ? "" : unit.after().text();
        if (code != null && !code.isBlank()) {
            String trimmed = code.strip();
            if (trimmed.length() > MAX_CODE_CHARS) {
                trimmed = trimmed.substring(0, MAX_CODE_CHARS);
            }
            query = query + "；改动后的代码：" + trimmed;
        }
        return query;
    }

    /** How a structural change reads to someone who knows the codebase, in the clauses' language. */
    static String describe(StructuralChange.Kind kind) {
        return switch (kind) {
            case UNIT_ADDED -> "新增了一个成员";
            case UNIT_REMOVED -> "删除了一个成员";
            case SIGNATURE_CHANGED -> "方法的签名变了";
            case VISIBILITY_CHANGED -> "可见性变了";
            case MODIFIER_CHANGED -> "修饰符变了";
            case THROWS_CHANGED -> "抛出异常声明变了";
            case ANNOTATION_REMOVED -> "删除了一条注解";
            case ANNOTATION_ADDED -> "新增了一条注解";
            case NULL_CHECK_REMOVED -> "删掉了空值检查";
            case CONDITION_INVERTED -> "条件判断被取反";
            case SWALLOWED_EXCEPTION -> "异常被吞掉或只记录日志";
            case RESOURCE_UNCLOSED -> "资源获取后没有关闭";
            case EARLY_EXIT_REMOVED -> "提前返回的分支被删除";
            case STATEMENTS_DELETED -> "删除了一条语句";
            case STATEMENTS_ADDED -> "新增了一条语句";
            case STATEMENTS_REORDERED -> "语句顺序被调换";
            case OPERATOR_CHANGED -> "运算符变了";
            case LITERAL_CHANGED -> "字面量或常量的值变了";
            case NULL_ARGUMENT_ADDED -> "新增的调用传入了 null";
            case TYPE_LEVEL_CHANGED -> "类级别的改动，涉及导入或类头";
            case UNPARSED -> "这段代码无法解析";
        };
    }
}
