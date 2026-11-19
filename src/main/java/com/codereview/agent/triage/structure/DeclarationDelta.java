package com.codereview.agent.triage.structure;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.Type;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Compares everything about a declaration except its body: visibility, modifiers, return
 * type, parameter types, thrown types, annotations.
 *
 * <p>Parameter names are ignored. Renaming a parameter is not a signature change, and
 * treating it as one would produce a review comment on every refactoring.
 */
final class DeclarationDelta {

    private static final Set<String> ACCESS_KEYWORDS = Set.of("public", "protected", "private");

    private DeclarationDelta() {
    }

    static void compare(BodyDeclaration<?> before, BodyDeclaration<?> after, int line, List<StructuralChange> changes) {
        if (!before.getClass().equals(after.getClass())) {
            changes.add(StructuralChange.exact(StructuralChange.Kind.SIGNATURE_CHANGED, line,
                    "declaration kind changed from " + before.getClass().getSimpleName()
                            + " to " + after.getClass().getSimpleName(),
                    before.toString(), after.toString()));
            return;
        }
        compareVisibility(before, after, line, changes);
        compareModifiers(before, after, line, changes);
        compareSignature(before, after, line, changes);
        compareThrows(before, after, line, changes);
        compareAnnotations(before, after, line, changes);
    }

    private static void compareVisibility(BodyDeclaration<?> before, BodyDeclaration<?> after,
                                          int line, List<StructuralChange> changes) {
        String oldVisibility = visibilityOf(before);
        String newVisibility = visibilityOf(after);
        if (oldVisibility.equals(newVisibility)) {
            return;
        }
        changes.add(StructuralChange.exact(StructuralChange.Kind.VISIBILITY_CHANGED, line,
                "visibility " + oldVisibility + " -> " + newVisibility,
                before.toString(), after.toString()));
    }

    private static void compareModifiers(BodyDeclaration<?> before, BodyDeclaration<?> after,
                                         int line, List<StructuralChange> changes) {
        Set<String> oldModifiers = modifiersOf(before);
        Set<String> newModifiers = modifiersOf(after);
        for (String removed : difference(oldModifiers, newModifiers)) {
            changes.add(StructuralChange.exact(StructuralChange.Kind.MODIFIER_CHANGED, line,
                    "modifier `" + removed + "` removed", before.toString(), after.toString()));
        }
        for (String added : difference(newModifiers, oldModifiers)) {
            changes.add(StructuralChange.exact(StructuralChange.Kind.MODIFIER_CHANGED, line,
                    "modifier `" + added + "` added", before.toString(), after.toString()));
        }
    }

    private static void compareSignature(BodyDeclaration<?> before, BodyDeclaration<?> after,
                                         int line, List<StructuralChange> changes) {
        String oldSignature = signatureOf(before);
        String newSignature = signatureOf(after);
        if (!oldSignature.equals(newSignature)) {
            changes.add(StructuralChange.exact(StructuralChange.Kind.SIGNATURE_CHANGED, line,
                    oldSignature + " -> " + newSignature, before.toString(), after.toString()));
        }
    }

    private static void compareThrows(BodyDeclaration<?> before, BodyDeclaration<?> after,
                                      int line, List<StructuralChange> changes) {
        Set<String> oldThrows = thrownTypesOf(before);
        Set<String> newThrows = thrownTypesOf(after);
        if (oldThrows.equals(newThrows)) {
            return;
        }
        for (String removed : difference(oldThrows, newThrows)) {
            changes.add(StructuralChange.exact(StructuralChange.Kind.THROWS_CHANGED, line,
                    "no longer declares `throws " + removed + "`", before.toString(), after.toString()));
        }
        for (String added : difference(newThrows, oldThrows)) {
            changes.add(StructuralChange.exact(StructuralChange.Kind.THROWS_CHANGED, line,
                    "now declares `throws " + added + "`", before.toString(), after.toString()));
        }
    }

    private static void compareAnnotations(BodyDeclaration<?> before, BodyDeclaration<?> after,
                                           int line, List<StructuralChange> changes) {
        Set<String> oldAnnotations = annotationsOf(before);
        Set<String> newAnnotations = annotationsOf(after);
        for (String removed : difference(oldAnnotations, newAnnotations)) {
            changes.add(StructuralChange.exact(StructuralChange.Kind.ANNOTATION_REMOVED, line,
                    "annotation `@" + removed + "` removed", before.toString(), after.toString()));
        }
        for (String added : difference(newAnnotations, oldAnnotations)) {
            changes.add(StructuralChange.exact(StructuralChange.Kind.ANNOTATION_ADDED, line,
                    "annotation `@" + added + "` added", before.toString(), after.toString()));
        }
    }

    private static String visibilityOf(BodyDeclaration<?> declaration) {
        for (String keyword : ACCESS_KEYWORDS) {
            if (rawModifiers(declaration).contains(keyword)) {
                return keyword;
            }
        }
        return "package-private";
    }

    private static Set<String> modifiersOf(Node declaration) {
        return rawModifiers(declaration).stream()
                .filter(keyword -> !ACCESS_KEYWORDS.contains(keyword))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> rawModifiers(Node declaration) {
        if (declaration instanceof NodeWithModifiers<?> withModifiers) {
            return withModifiers.getModifiers().stream()
                    .map(modifier -> modifier.getKeyword().asString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        return Set.of();
    }

    /** Return type plus parameter types: the part of a signature callers compile against. */
    private static String signatureOf(BodyDeclaration<?> declaration) {
        if (declaration instanceof MethodDeclaration method) {
            return method.getType().asString() + " " + method.getNameAsString() + parameterTypes(method);
        }
        if (declaration instanceof CallableDeclaration<?> callable) {
            return "ctor" + parameterTypes(callable);
        }
        if (declaration instanceof FieldDeclaration field) {
            Set<String> types = new TreeSet<>();
            field.getVariables().forEach(variable -> types.add(variable.getType().asString()));
            return String.join("|", types);
        }
        return "";
    }

    private static String parameterTypes(CallableDeclaration<?> callable) {
        StringJoiner joiner = new StringJoiner(", ", "(", ")");
        for (var parameter : callable.getParameters()) {
            Type type = parameter.getType();
            joiner.add(type.asString() + (parameter.isVarArgs() ? "..." : ""));
        }
        return joiner.toString();
    }

    private static Set<String> thrownTypesOf(BodyDeclaration<?> declaration) {
        List<? extends Node> thrown;
        if (declaration instanceof MethodDeclaration method) {
            thrown = method.getThrownExceptions();
        } else if (declaration instanceof ConstructorDeclaration constructor) {
            thrown = constructor.getThrownExceptions();
        } else {
            return Set.of();
        }
        return thrown.stream()
                .map(Node::toString)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> annotationsOf(BodyDeclaration<?> declaration) {
        if (declaration instanceof NodeWithAnnotations<?> withAnnotations) {
            return withAnnotations.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return Set.of();
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> difference = new TreeSet<>(left);
        difference.removeAll(right);
        return difference;
    }
}
