package com.codereview.agent.triage.staticanalysis;

import com.codereview.agent.triage.diff.FileChange;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.CatchClause;

import java.util.ArrayList;
import java.util.List;

/**
 * Source-level checks for defects that a structural diff cannot see.
 *
 * <p>The shape comparison answers "what changed"; these answer "what the new code is". They are
 * deliberately few and narrow: each one has a story that ends in a real bug, and none of them
 * guesses. A full analyzer (SpotBugs, needing a compiled target) plugs into the same interface; the
 * built-in set exists because the demo reviews repositories that were never built.
 *
 * <p>The string check only fires when one side is a literal, which costs recall and buys
 * precision: flagging every {@code ==} between two variables would report arithmetic comparisons
 * on every file, and a review tool that cries wolf is worse than one that stays quiet.
 *
 * <p>Every check runs on the head version only. Filtering to added lines happens in
 * {@link #onAddedLines(FileChange)}, so a pre-existing warning in an untouched method never
 * reaches triage.
 */
public final class SourceStaticAnalysis implements StaticAnalysis {

    @Override
    public List<StaticWarning> analyse(String path, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);
        CompilationUnit unit = new JavaParser(configuration).parse(text).getResult().orElse(null);
        if (unit == null) {
            return List.of();
        }
        List<StaticWarning> warnings = new ArrayList<>();
        unit.findAll(BinaryExpr.class).forEach(expression -> {
            if (expression.getOperator() != BinaryExpr.Operator.EQUALS
                    && expression.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
                return;
            }
            if (isStringAgainstSomething(expression)) {
                warnings.add(new StaticWarning("STRING_EQUALITY", line(expression),
                        "用 " + (expression.getOperator() == BinaryExpr.Operator.EQUALS ? "==" : "!=")
                                + " 比较字符串，比较的是引用而不是内容"));
            }
        });
        unit.findAll(CatchClause.class).forEach(clause -> {
            String type = clause.getParameter().getType().asString();
            if ("Throwable".equals(type)) {
                warnings.add(new StaticWarning("CATCH_THROWABLE", line(clause),
                        "捕获 Throwable 会把 Error 一起吞掉"));
            }
        });
        unit.findAll(MethodCallExpr.class).forEach(call -> {
            if ("printStackTrace".equals(call.getNameAsString())) {
                warnings.add(new StaticWarning("PRINT_STACK_TRACE", line(call),
                        "用 printStackTrace 输出异常，日志系统收不到"));
            } else if (isStandardStream(call) && !path.contains("/test/")) {
                warnings.add(new StaticWarning("DEBUG_PRINT", line(call),
                        "生产代码直接写标准输出，日志系统收不到"));
            }
        });
        return warnings;
    }

    private static boolean isStringAgainstSomething(BinaryExpr expression) {
        Expression left = expression.getLeft();
        Expression right = expression.getRight();
        boolean leftIsLiteral = left instanceof StringLiteralExpr;
        boolean rightIsLiteral = right instanceof StringLiteralExpr;
        if (leftIsLiteral == rightIsLiteral) {
            return false;
        }
        Expression other = leftIsLiteral ? right : left;
        return !(other instanceof NullLiteralExpr);
    }

    private static boolean isStandardStream(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (!name.startsWith("print")) {
            return false;
        }
        return call.getScope().filter(scope -> scope instanceof FieldAccessExpr field
                && ("out".equals(field.getNameAsString()) || "err".equals(field.getNameAsString()))
                && field.getScope().isNameExpr()
                && "System".equals(((com.github.javaparser.ast.expr.NameExpr) field.getScope())
                        .getNameAsString())).isPresent();
    }

    private static int line(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }
}
