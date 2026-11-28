package com.codereview.agent.triage.rules;

import com.codereview.agent.triage.structure.StructuralChange;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The hand-written half of triage.
 *
 * <p>Eight rules, each one a shape that can be decided by looking at the difference and
 * nothing else. The list is meant to stay short: every rule added here is a rule that can
 * be wrong in production, so a rule earns its place by being either certain or cheap.
 *
 * <p>Rules are also measured. Because every hit records the changes that caused it, the
 * evaluation stage can group outcomes by rule id and ask which rules ever led to a real
 * finding. A rule that produces investigations but never findings is noise and should be
 * retired.
 */
public final class RuleCatalog {

    private static final Pattern SENSITIVE_PATH = Pattern.compile(
            "(?i)(^|/)(auth|authn|authz|security|login|logout|session|token|oauth|crypto|"
                    + "payment|billing|checkout|migration|migrations|permission|acl)(/|\\.|$)"
                    + "|(?i)(auth|login|token|password|crypto|payment|security|permission)"
                    + "(service|controller|filter|interceptor|manager|handler)?\\.java$");

    /** What each built-in rule means, in the words the report can show to an author. */
    private static final java.util.Map<String, String> EXPLANATIONS = java.util.Map.ofEntries(
            java.util.Map.entry("NULL-CHECK-REMOVED",
                    "判空检查消失了，值可能未经检查就到达使用点"),
            java.util.Map.entry("CONTRACT-CHANGED",
                    "方法的可调用契约变了（可见性、签名、throws 或注解）"),
            java.util.Map.entry("EXCEPTION-SWALLOWED",
                    "新增的 catch 吞掉了异常，既没有处理也没有往上抛"),
            java.util.Map.entry("RESOURCE-LEAK",
                    "可关闭资源没有放进 try-with-resources，是否在别处关闭需要看一眼"),
            java.util.Map.entry("CONTROL-FLOW-CHANGED",
                    "控制流形状变了，原来守住的判空或提前返回不在了"),
            java.util.Map.entry("REORDERED-STATEMENTS",
                    "同样的语句换了顺序，副作用发生的次序变了"),
            java.util.Map.entry("OPERATOR-CHANGED",
                    "比较或算术运算符变了，边界错误常出在这里"),
            java.util.Map.entry("LITERAL-CHANGED",
                    "数字或字符串字面量变了，常量里往往藏着意图"),
            java.util.Map.entry("NULL-ARGUMENT",
                    "新增的调用传了 null 实参，被调方必须接得住"),
            java.util.Map.entry("SENSITIVE-PATH",
                    "改动落在认证、支付或迁移这类敏感路径上"),
            java.util.Map.entry("CONCURRENCY-SEMANTICS",
                    "synchronized 或 volatile 变了，代码对并发作出的保证随之改变"),
            java.util.Map.entry("PURE-DELETION",
                    "删掉了代码却没有替代，调用方可能还依赖它"),
            java.util.Map.entry("STATIC-CORRECTNESS",
                    "静态检查在新增代码上报告了确定的缺陷（字符串用 == 比较、捕获 Throwable）"),
            java.util.Map.entry("POLICY-CONSTRAINT",
                    "仓库的政策文档把这一块标成强制，改动必须逐条对照"),
            java.util.Map.entry("STATIC-STYLE",
                    "静态检查在新增代码上报告了可疑写法（直接写标准输出、printStackTrace）"));

    private final List<Rule> rules;

    /**
     * A one-line Chinese explanation for a built-in rule, or null for a rule a caller supplied.
     */
    public static String explain(String id) {
        return EXPLANATIONS.get(id);
    }

    public RuleCatalog(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static RuleCatalog defaults() {
        List<Rule> defaults = new ArrayList<>();

        defaults.add(new Rule("NULL-CHECK-REMOVED", RiskCategory.CONSISTENCY, true,
                "a null guard disappeared; the value may now reach a use site unguarded",
                context -> context.has(StructuralChange.Kind.NULL_CHECK_REMOVED)));

        defaults.add(new Rule("CONTRACT-CHANGED", RiskCategory.CONTRACT, true,
                "the unit's callable contract changed: visibility, signature, throws, or an annotation",
                context -> context.has(StructuralChange.Kind.VISIBILITY_CHANGED)
                        || context.has(StructuralChange.Kind.SIGNATURE_CHANGED)
                        || context.has(StructuralChange.Kind.THROWS_CHANGED)
                        || context.has(StructuralChange.Kind.ANNOTATION_REMOVED)
                        || context.has(StructuralChange.Kind.UNIT_REMOVED)));

        defaults.add(new Rule("EXCEPTION-SWALLOWED", RiskCategory.CONSISTENCY, true,
                "a new catch block drops the failure instead of propagating or handling it",
                context -> context.has(StructuralChange.Kind.SWALLOWED_EXCEPTION)));

        defaults.add(new Rule("RESOURCE-LEAK", RiskCategory.RESOURCE, false,
                "a closeable was acquired outside try-with-resources; whether it is closed later needs a look",
                context -> context.has(StructuralChange.Kind.RESOURCE_UNCLOSED)));

        // Control flow is the one family where the shape itself says "this changes what the code
        // does": the guard that used to hold no longer does, or a return that used to stop the
        // method is gone. A model is not asked whether that deserves a look.
        defaults.add(new Rule("CONTROL-FLOW-CHANGED", RiskCategory.LOGIC, true,
                "control flow changed shape; the guard or the early exit that used to hold no longer does",
                context -> context.has(StructuralChange.Kind.CONDITION_INVERTED)
                        || context.has(StructuralChange.Kind.EARLY_EXIT_REMOVED)));

        defaults.add(new Rule("REORDERED-STATEMENTS", RiskCategory.LOGIC, true,
                "the same statements moved; side effects now happen in a different order",
                context -> context.has(StructuralChange.Kind.STATEMENTS_REORDERED)));

        defaults.add(new Rule("OPERATOR-CHANGED", RiskCategory.LOGIC, true,
                "a comparison or arithmetic operator changed, which is where boundary bugs live",
                context -> context.has(StructuralChange.Kind.OPERATOR_CHANGED)));

        defaults.add(new Rule("LITERAL-CHANGED", RiskCategory.LOGIC, true,
                "a number or string literal is not what it was, and constants are where intent hides",
                context -> context.has(StructuralChange.Kind.LITERAL_CHANGED)));

        defaults.add(new Rule("NULL-ARGUMENT", RiskCategory.CONSISTENCY, true,
                "an added call passes a null literal, which the callee must be able to absorb",
                context -> context.has(StructuralChange.Kind.NULL_ARGUMENT_ADDED)));

        defaults.add(new Rule("SENSITIVE-PATH", RiskCategory.SECURITY, true,
                "the unit lives where auth, payment or migrations are handled",
                context -> SENSITIVE_PATH.matcher(context.path()).find()));

        defaults.add(new Rule("CONCURRENCY-SEMANTICS", RiskCategory.CONCURRENCY, true,
                "a synchronization keyword changed, which changes what the code guarantees",
                context -> context.of(StructuralChange.Kind.MODIFIER_CHANGED).stream()
                        .anyMatch(change -> mentions(change, "synchronized") || mentions(change, "volatile"))));

        // Deleting code without a replacement is the cheapest thing to recognise and the easiest
        // to be wrong about: whatever the removed lines promised, nothing else now keeps the
        // promise. Like control flow, this does not need a model's opinion.
        defaults.add(new Rule("PURE-DELETION", RiskCategory.LOGIC, true,
                "code was removed with nothing added in its place; the callers may still expect it",
                context -> context.unit().removedLines() > 0 && context.unit().addedLines() == 0));

        // The static checks answer a different question from the shape rules: not "what changed"
        // but "what the new code is". A string compared with == is a defect the diff cannot show,
        // because before and after look the same.
        defaults.add(new Rule("STATIC-CORRECTNESS", RiskCategory.CONSISTENCY, true,
                "静态检查在新增代码上报告了确定的缺陷（字符串字面量用 == 比较、捕获 Throwable）",
                context -> !context.warningsOf("STRING_EQUALITY", "CATCH_THROWABLE").isEmpty()));

        // A policy document marked this area as mandatory. The author of the document is the one
        // saying "any change here has to be checked", so this gates on a fact, like the path rule,
        // and the model never gets to decide whether the area matters.
        defaults.add(new Rule("POLICY-CONSTRAINT", RiskCategory.CONSISTENCY, true,
                "仓库的政策文档把这一块标成强制，改动必须逐条对照",
                context -> context.policyMandatory()));

        defaults.add(new Rule("STATIC-STYLE", RiskCategory.LOGIC, false,
                "静态检查在新增代码上报告了可疑写法（直接写标准输出、printStackTrace）",
                context -> context.warnings().stream()
                        .anyMatch(warning -> !"STRING_EQUALITY".equals(warning.kind())
                                && !"CATCH_THROWABLE".equals(warning.kind()))));

        return new RuleCatalog(defaults);
    }

    private static boolean mentions(StructuralChange change, String keyword) {
        return change.detail().toLowerCase(java.util.Locale.ROOT).contains(keyword);
    }

    public List<Rule> rules() {
        return rules;
    }

    public List<RuleHit> evaluate(RuleContext context) {
        List<RuleHit> hits = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.matches().test(context)) {
                hits.add(new RuleHit(rule, evidenceFor(rule, context)));
            }
        }
        return hits;
    }

    /**
     * Attributes a hit to its evidence where the rule is about specific changes. Rules that
     * read the path or the line counts have no changes to point at, which is honest: they
     * gate on a fact, not on a recognised shape.
     */
    private static List<StructuralChange> evidenceFor(Rule rule, RuleContext context) {
        return switch (rule.id()) {
            case "POLICY-CONSTRAINT" -> List.of();
            case "NULL-CHECK-REMOVED" -> context.of(StructuralChange.Kind.NULL_CHECK_REMOVED);
            case "CONTRACT-CHANGED" -> union(context,
                    StructuralChange.Kind.VISIBILITY_CHANGED,
                    StructuralChange.Kind.SIGNATURE_CHANGED,
                    StructuralChange.Kind.THROWS_CHANGED,
                    StructuralChange.Kind.ANNOTATION_REMOVED,
                    StructuralChange.Kind.UNIT_REMOVED);
            case "EXCEPTION-SWALLOWED" -> context.of(StructuralChange.Kind.SWALLOWED_EXCEPTION);
            case "RESOURCE-LEAK" -> context.of(StructuralChange.Kind.RESOURCE_UNCLOSED);
            case "CONTROL-FLOW-CHANGED" -> union(context, StructuralChange.Kind.CONDITION_INVERTED,
                    StructuralChange.Kind.EARLY_EXIT_REMOVED);
            case "CONCURRENCY-SEMANTICS" -> context.of(StructuralChange.Kind.MODIFIER_CHANGED);
            default -> List.of();
        };
    }

    private static List<StructuralChange> union(RuleContext context, StructuralChange.Kind... kinds) {
        List<StructuralChange> evidence = new ArrayList<>();
        for (StructuralChange.Kind kind : kinds) {
            evidence.addAll(context.of(kind));
        }
        return evidence;
    }
}
