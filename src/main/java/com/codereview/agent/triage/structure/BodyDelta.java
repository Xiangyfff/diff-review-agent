package com.codereview.agent.triage.structure;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Diffs the statements of a method (or constructor / initializer) and recognises the
 * shapes of the differences.
 *
 * <p>Shape recognition works on a pair: what disappeared next to what appeared. That is
 * what separates this from a pattern matcher, which only sees the code as it is now and
 * therefore cannot tell "this guard was never there" from "this guard was just removed".
 *
 * <p>Every statement consumed by a specific recogniser is marked explained, so the
 * leftover count at the end means "something changed that no rule understands". Those
 * leftovers still get reported as plain added/removed counts rather than being dropped.
 */
final class BodyDelta {

    /** Calls whose sole purpose is to reject a null argument. */
    private static final Set<String> NON_NULL_CHECKS = Set.of("requireNonNull", "checkNotNull", "notNull");

    /**
     * JDK types that hold an OS handle and must be closed. Matched on the simple name, so
     * a project-local wrapper with the same name is a false positive; that is why the
     * change is reported as HEURISTIC and cannot gate a unit on its own.
     */
    private static final Set<String> CLOSEABLE_TYPES = Set.of(
            "FileInputStream", "FileOutputStream", "FileReader", "FileWriter",
            "BufferedInputStream", "BufferedOutputStream", "BufferedReader", "BufferedWriter",
            "InputStreamReader", "OutputStreamWriter", "RandomAccessFile", "Scanner", "Formatter",
            "ZipFile", "JarFile", "Socket", "ServerSocket", "DatagramSocket",
            "FileChannel", "SocketChannel", "ServerSocketChannel", "AsynchronousFileChannel",
            "Connection", "Statement", "PreparedStatement", "ResultSet",
            "InputStream", "OutputStream", "Reader", "Writer");

    private BodyDelta() {
    }

    static void compare(BodyDeclaration<?> before, BodyDeclaration<?> after, int line, List<StructuralChange> changes) {
        List<Statement> oldStatements = statementsOf(before);
        List<Statement> newStatements = statementsOf(after);
        if (oldStatements.isEmpty() && newStatements.isEmpty()) {
            return;
        }
        Set<String> localNames = localNamesOf(before, after);
        for (StatementSequenceDiff.Block block : StatementSequenceDiff.diff(oldStatements, newStatements, localNames)) {
            classify(block, line, changes);
        }
    }

    private static List<Statement> statementsOf(BodyDeclaration<?> member) {
        if (member instanceof MethodDeclaration method) {
            return method.getBody().map(body -> List.copyOf(body.getStatements())).orElseGet(List::of);
        }
        if (member instanceof ConstructorDeclaration constructor) {
            return List.copyOf(constructor.getBody().getStatements());
        }
        if (member instanceof InitializerDeclaration initializer) {
            return List.copyOf(initializer.getBody().getStatements());
        }
        return List.of();
    }

    private static Set<String> localNamesOf(BodyDeclaration<?> before, BodyDeclaration<?> after) {
        Set<String> names = new TreeSet<>();
        for (BodyDeclaration<?> member : List.of(before, after)) {
            member.findAll(VariableDeclarator.class).forEach(variable -> names.add(variable.getNameAsString()));
            member.findAll(LambdaExpr.class)
                    .forEach(lambda -> lambda.getParameters().forEach(p -> names.add(p.getNameAsString())));
            member.findAll(CatchClause.class)
                    .forEach(clause -> names.add(clause.getParameter().getNameAsString()));
        }
        return names;
    }

    private static void classify(StatementSequenceDiff.Block block, int line, List<StructuralChange> changes) {
        List<Statement> removed = block.removed();
        List<Statement> added = block.added();
        Set<Statement> explained = Collections.newSetFromMap(new IdentityHashMap<>());

        detectRemovedNullGuard(removed, added, line, changes, explained);
        detectInvertedCondition(removed, added, line, changes, explained);
        detectSwallowedException(added, line, changes, explained);
        detectUnclosedResource(added, line, changes, explained);
        detectRemovedEarlyExit(removed, added, line, changes, explained);

        reportLeftovers(removed, added, explained, line, changes);
    }

    // ---- shape: a null guard that used to be here is gone ------------------------------

    private static void detectRemovedNullGuard(List<Statement> removed, List<Statement> added, int line,
                                               List<StructuralChange> changes, Set<Statement> explained) {
        for (Statement candidate : removed) {
            String guarded = nullGuardedExpression(candidate);
            if (guarded == null) {
                continue;
            }
            boolean stillGuarded = added.stream()
                    .map(BodyDelta::nullGuardedExpression)
                    .anyMatch(guarded::equals);
            if (stillGuarded) {
                // Moved to another statement in the same block: a rewrite, not a removal.
                explained.add(candidate);
                continue;
            }
            changes.add(StructuralChange.exact(StructuralChange.Kind.NULL_CHECK_REMOVED, line,
                    "null guard on `" + guarded + "` is gone", candidate.toString(), ""));
            explained.add(candidate);
        }
    }

    /** Returns the expression a statement null-checks, or null when it does not check one. */
    private static String nullGuardedExpression(Statement statement) {
        if (statement instanceof IfStmt ifStatement) {
            return nullComparedOperand(ifStatement.getCondition());
        }
        if (statement instanceof ExpressionStmt expressionStatement
                && expressionStatement.getExpression() instanceof MethodCallExpr call
                && NON_NULL_CHECKS.contains(call.getNameAsString())
                && !call.getArguments().isEmpty()) {
            return collapse(call.getArgument(0));
        }
        return null;
    }

    private static String nullComparedOperand(Expression condition) {
        Expression expression = strip(condition);
        if (expression instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return nullComparedOperand(unary.getExpression());
        }
        if (!(expression instanceof BinaryExpr binary)) {
            return null;
        }
        BinaryExpr.Operator operator = binary.getOperator();
        if (operator != BinaryExpr.Operator.EQUALS && operator != BinaryExpr.Operator.NOT_EQUALS) {
            return null;
        }
        String left = collapse(binary.getLeft());
        String right = collapse(binary.getRight());
        if ("null".equals(left)) {
            return right;
        }
        if ("null".equals(right)) {
            return left;
        }
        return null;
    }

    // ---- shape: the condition flipped ---------------------------------------------------

    private static void detectInvertedCondition(List<Statement> removed, List<Statement> added, int line,
                                                List<StructuralChange> changes, Set<Statement> explained) {
        List<IfStmt> before = filter(removed, IfStmt.class);
        List<IfStmt> after = filter(added, IfStmt.class);
        if (before.size() != 1 || after.size() != 1) {
            return;
        }
        IfStmt oldIf = before.get(0);
        IfStmt newIf = after.get(0);
        if (!areNegations(oldIf.getCondition(), newIf.getCondition())) {
            return;
        }
        explained.add(oldIf);
        explained.add(newIf);
        if (branchesSwapped(oldIf, newIf)) {
            // `if (!c) A else B` rewritten as `if (c) B else A`: same behaviour, no finding.
            return;
        }
        changes.add(StructuralChange.exact(StructuralChange.Kind.CONDITION_INVERTED, line,
                "condition `" + collapse(oldIf.getCondition()) + "` became `" + collapse(newIf.getCondition()) + "`",
                oldIf.toString(), newIf.toString()));
    }

    private static boolean areNegations(Expression before, Expression after) {
        Expression left = strip(before);
        Expression right = strip(after);
        if (left instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
                && collapse(unary.getExpression()).equals(collapse(right))) {
            return true;
        }
        if (right instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
                && collapse(unary.getExpression()).equals(collapse(left))) {
            return true;
        }
        if (left instanceof BinaryExpr oldBinary && right instanceof BinaryExpr newBinary) {
            String oldLeft = collapse(oldBinary.getLeft());
            String oldRight = collapse(oldBinary.getRight());
            String newLeft = collapse(newBinary.getLeft());
            String newRight = collapse(newBinary.getRight());
            if (oldLeft.equals(newLeft) && oldRight.equals(newRight)) {
                return isOpposite(oldBinary.getOperator(), newBinary.getOperator());
            }
            if (oldLeft.equals(newRight) && oldRight.equals(newLeft)) {
                return isOppositeWithSwappedOperands(oldBinary.getOperator(), newBinary.getOperator());
            }
        }
        return false;
    }

    private static boolean isOpposite(BinaryExpr.Operator before, BinaryExpr.Operator after) {
        return switch (before) {
            case LESS -> after == BinaryExpr.Operator.GREATER_EQUALS;
            case GREATER -> after == BinaryExpr.Operator.LESS_EQUALS;
            case LESS_EQUALS -> after == BinaryExpr.Operator.GREATER;
            case GREATER_EQUALS -> after == BinaryExpr.Operator.LESS;
            case EQUALS -> after == BinaryExpr.Operator.NOT_EQUALS;
            case NOT_EQUALS -> after == BinaryExpr.Operator.EQUALS;
            default -> false;
        };
    }

    /** {@code a < b} and {@code b <= a} are negations of each other. */
    private static boolean isOppositeWithSwappedOperands(BinaryExpr.Operator before, BinaryExpr.Operator after) {
        return switch (before) {
            case LESS -> after == BinaryExpr.Operator.LESS_EQUALS;
            case LESS_EQUALS -> after == BinaryExpr.Operator.LESS;
            case GREATER -> after == BinaryExpr.Operator.GREATER_EQUALS;
            case GREATER_EQUALS -> after == BinaryExpr.Operator.GREATER;
            case EQUALS -> after == BinaryExpr.Operator.EQUALS;
            case NOT_EQUALS -> after == BinaryExpr.Operator.NOT_EQUALS;
            default -> false;
        };
    }

    private static boolean branchesSwapped(IfStmt before, IfStmt after) {
        if (before.getElseStmt().isEmpty() || after.getElseStmt().isEmpty()) {
            return false;
        }
        return collapse(before.getThenStmt()).equals(collapse(after.getElseStmt().get()))
                && collapse(before.getElseStmt().get()).equals(collapse(after.getThenStmt()));
    }

    // ---- shape: a new catch block that hides the exception ------------------------------

    private static void detectSwallowedException(List<Statement> added, int line,
                                                 List<StructuralChange> changes, Set<Statement> explained) {
        for (Statement statement : added) {
            for (TryStmt tryStatement : statement.findAll(TryStmt.class)) {
                for (CatchClause clause : tryStatement.getCatchClauses()) {
                    List<Statement> body = clause.getBody().getStatements();
                    boolean empty = body.isEmpty();
                    if (!empty && !body.stream().allMatch(BodyDelta::isLoggingOnly)) {
                        continue;
                    }
                    String caught = clause.getParameter().getType().asString();
                    String detail = empty
                            ? "new catch block for `" + caught + "` does nothing"
                            : "new catch block for `" + caught + "` only logs";
                    StructuralChange change = empty
                            ? StructuralChange.exact(StructuralChange.Kind.SWALLOWED_EXCEPTION, line, detail, "", clause.toString())
                            : StructuralChange.heuristic(StructuralChange.Kind.SWALLOWED_EXCEPTION, line, detail, "", clause.toString());
                    changes.add(change);
                    explained.add(statement);
                }
            }
        }
    }

    private static boolean isLoggingOnly(Statement statement) {
        if (!(statement instanceof ExpressionStmt expressionStatement)
                || !(expressionStatement.getExpression() instanceof MethodCallExpr call)) {
            return false;
        }
        if (call.getScope().isEmpty()) {
            return false;
        }
        String scope = collapse(call.getScope().get()).toLowerCase(java.util.Locale.ROOT);
        return scope.contains("log") || scope.contains("logger") || "system".equals(scope)
                || scope.endsWith("out") || scope.endsWith("err");
    }

    // ---- shape: a resource acquired without try-with-resources -------------------------

    private static void detectUnclosedResource(List<Statement> added, int line,
                                               List<StructuralChange> changes, Set<Statement> explained) {
        for (Statement statement : added) {
            VariableDeclarator acquired = acquiredCloseable(statement);
            if (acquired == null) {
                continue;
            }
            String type = acquired.getType().asString();
            changes.add(StructuralChange.heuristic(StructuralChange.Kind.RESOURCE_UNCLOSED, line,
                    "`" + type + "` acquired outside try-with-resources", statement.toString(), ""));
            explained.add(statement);
        }
    }

    private static VariableDeclarator acquiredCloseable(Statement statement) {
        if (!(statement instanceof ExpressionStmt expressionStatement)
                || !(expressionStatement.getExpression() instanceof VariableDeclarationExpr declaration)) {
            return null;
        }
        if (declaration.getVariables().size() != 1) {
            return null;
        }
        VariableDeclarator variable = declaration.getVariable(0);
        if (variable.getInitializer().isEmpty()
                || !(variable.getInitializer().get() instanceof ObjectCreationExpr creation)) {
            return null;
        }
        if (!CLOSEABLE_TYPES.contains(creation.getType().getNameAsString())) {
            return null;
        }
        return variable;
    }

    // ---- shape: an early exit that disappeared -----------------------------------------

    private static void detectRemovedEarlyExit(List<Statement> removed, List<Statement> added, int line,
                                               List<StructuralChange> changes, Set<Statement> explained) {
        // A return swapped for another return is a rewrite, not a lost exit: the method still
        // stops where it used to. Reporting it as a removed exit turns every rename inside a
        // return into a control-flow change, and control-flow changes are not left to the model.
        boolean exitStillThere = added.stream()
                .anyMatch(statement -> statement instanceof ReturnStmt || statement instanceof ThrowStmt);
        if (exitStillThere) {
            return;
        }
        for (Statement statement : removed) {
            if (statement instanceof ReturnStmt) {
                changes.add(StructuralChange.exact(StructuralChange.Kind.EARLY_EXIT_REMOVED, line,
                        "`return` statement removed", statement.toString(), ""));
                explained.add(statement);
            } else if (statement instanceof ThrowStmt) {
                changes.add(StructuralChange.exact(StructuralChange.Kind.EARLY_EXIT_REMOVED, line,
                        "`throw` statement removed", statement.toString(), ""));
                explained.add(statement);
            }
        }
    }

    private static void reportLeftovers(List<Statement> removed, List<Statement> added, Set<Statement> explained,
                                        int line, List<StructuralChange> changes) {
        List<Statement> unexplainedRemoved = unexplained(removed, explained);
        List<Statement> unexplainedAdded = unexplained(added, explained);
        if (!unexplainedRemoved.isEmpty()) {
            changes.add(StructuralChange.exact(StructuralChange.Kind.STATEMENTS_DELETED, line,
                    unexplainedRemoved.size() + " statement(s) removed with no recognised shape",
                    collapse(unexplainedRemoved.get(0)), ""));
        }
        if (!unexplainedAdded.isEmpty()) {
            changes.add(StructuralChange.exact(StructuralChange.Kind.STATEMENTS_ADDED, line,
                    unexplainedAdded.size() + " statement(s) added with no recognised shape",
                    collapse(unexplainedAdded.get(0)), ""));
        }
    }

    private static List<Statement> unexplained(List<Statement> statements, Set<Statement> explained) {
        List<Statement> leftovers = new ArrayList<>();
        for (Statement statement : statements) {
            if (!explained.contains(statement)) {
                leftovers.add(statement);
            }
        }
        return leftovers;
    }

    private static <T extends Statement> List<T> filter(List<Statement> statements, Class<T> type) {
        List<T> matches = new ArrayList<>();
        for (Statement statement : statements) {
            if (type.isInstance(statement)) {
                matches.add(type.cast(statement));
            }
        }
        return matches;
    }

    private static Expression strip(Expression expression) {
        Expression current = expression;
        while (current instanceof EnclosedExpr enclosed) {
            current = enclosed.getInner();
        }
        return current;
    }

    private static String collapse(Expression expression) {
        return collapse(expression.toString());
    }

    private static String collapse(Statement statement) {
        return collapse(statement.toString());
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
