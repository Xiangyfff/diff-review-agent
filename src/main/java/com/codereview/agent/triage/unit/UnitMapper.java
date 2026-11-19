package com.codereview.agent.triage.unit;

import com.codereview.agent.triage.diff.FileChange;
import com.codereview.agent.triage.parse.SourceParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Turns file-level hunks into function-level units.
 *
 * <p>For every hunk we ask the syntax tree which named declaration it lands in, on both
 * sides of the change, and pair the two answers by identity. Two hunks inside one method
 * therefore produce one unit, which is the whole point: a change is usually only
 * meaningful together with its neighbours.
 */
public final class UnitMapper {

    private static final String TYPE_LEVEL_NAME = "<type-level>";

    public List<AnalysisUnit> map(FileChange change, CompilationUnit before, CompilationUnit after) {
        Map<String, SourceUnit> beforeIndex = index(before);
        Map<String, SourceUnit> afterIndex = index(after);

        Map<String, Mutable> builders = new LinkedHashMap<>();
        List<FileChange.EditRange> typeLevelEdits = new ArrayList<>();

        switch (change.type()) {
            case ADDED -> afterIndex.forEach((key, unit) -> {
                Mutable unitBuilder = builders.computeIfAbsent(key, Mutable::new);
                unitBuilder.after = unit;
                unitBuilder.addedLines = span(unit);
            });
            case DELETED -> beforeIndex.forEach((key, unit) -> {
                Mutable unitBuilder = builders.computeIfAbsent(key, Mutable::new);
                unitBuilder.before = unit;
                unitBuilder.removedLines = span(unit);
            });
            default -> {
                for (FileChange.EditRange edit : change.edits()) {
                    List<SourceUnit> beforeUnits = locate(before, edit.oldStart(), edit.oldEnd());
                    List<SourceUnit> afterUnits = locate(after, edit.newStart(), edit.newEnd());
                    if (beforeUnits.isEmpty() && afterUnits.isEmpty()) {
                        typeLevelEdits.add(edit);
                        continue;
                    }
                    Set<String> keys = new LinkedHashSet<>();
                    beforeUnits.forEach(unit -> keys.add(unit.key()));
                    afterUnits.forEach(unit -> keys.add(unit.key()));
                    for (String key : keys) {
                        Mutable unitBuilder = builders.computeIfAbsent(key, Mutable::new);
                        unitBuilder.before = beforeIndex.get(key);
                        unitBuilder.after = afterIndex.get(key);
                        unitBuilder.edits.add(edit);
                    }
                }
            }
        }

        if (!typeLevelEdits.isEmpty()) {
            Mutable unitBuilder = new Mutable(SourceUnit.Kind.TYPE_LEVEL + "#" + TYPE_LEVEL_NAME);
            unitBuilder.edits.addAll(typeLevelEdits);
            unitBuilder.before = typeLevelRegion(change.oldText(), typeLevelEdits, false);
            unitBuilder.after = typeLevelRegion(change.newText(), typeLevelEdits, true);
            builders.put(unitBuilder.key, unitBuilder);
        }

        List<AnalysisUnit> units = new ArrayList<>();
        for (Mutable unitBuilder : builders.values()) {
            if (unitBuilder.before == null && unitBuilder.after == null) {
                continue;
            }
            if (unitBuilder.addedLines == 0 && unitBuilder.removedLines == 0) {
                unitBuilder.addedLines = coveredLines(unitBuilder.after, unitBuilder.edits, true);
                unitBuilder.removedLines = coveredLines(unitBuilder.before, unitBuilder.edits, false);
            }
            units.add(new AnalysisUnit(
                    change.displayPath(),
                    unitBuilder.key,
                    unitBuilder.before,
                    unitBuilder.after,
                    List.copyOf(unitBuilder.edits),
                    unitBuilder.addedLines,
                    unitBuilder.removedLines));
        }
        units.sort(Comparator.comparingInt(AnalysisUnit::startLine));
        return units;
    }

    /** Every named declaration in the file, indexed by a collision-free key. */
    Map<String, SourceUnit> index(CompilationUnit unit) {
        Map<String, SourceUnit> index = new LinkedHashMap<>();
        if (unit == null) {
            return index;
        }
        for (BodyDeclaration<?> member : members(unit)) {
            toUnit(member).ifPresent(sourceUnit -> index.putIfAbsent(sourceUnit.key(), sourceUnit));
        }
        return index;
    }

    /**
     * The innermost declarations that overlap a line range.
     *
     * <p>Overlap rather than containment: one hunk can delete the tail of one method and the
     * head of the next, and both deserve a unit. Containers are then dropped so a method is
     * never reported twice through its enclosing class.
     */
    List<SourceUnit> locate(CompilationUnit unit, int startLine, int endLine) {
        if (unit == null || startLine > endLine) {
            return List.of();
        }
        List<BodyDeclaration<?>> overlapping = members(unit).stream()
                .filter(member -> overlaps(member, startLine, endLine))
                .toList();
        if (overlapping.isEmpty()) {
            return List.of();
        }
        List<SourceUnit> result = new ArrayList<>();
        for (BodyDeclaration<?> member : overlapping) {
            boolean containsAnother = overlapping.stream()
                    .anyMatch(other -> other != member && contains(member, other));
            if (!containsAnother) {
                toUnit(member).ifPresent(result::add);
            }
        }
        return result;
    }

    private static List<BodyDeclaration<?>> members(CompilationUnit unit) {
        List<BodyDeclaration<?>> members = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            collect(type, members);
        }
        return members;
    }

    private static void collect(BodyDeclaration<?> member, List<BodyDeclaration<?>> out) {
        out.add(member);
        if (member instanceof TypeDeclaration<?> type) {
            for (BodyDeclaration<?> child : type.getMembers()) {
                collect(child, out);
            }
        }
    }

    private Optional<SourceUnit> toUnit(BodyDeclaration<?> member) {
        Optional<SourceUnit.Kind> kind = kindOf(member);
        if (kind.isEmpty() || member.getBegin().isEmpty() || member.getEnd().isEmpty()) {
            return Optional.empty();
        }
        String name = nameOf(member);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SourceUnit(
                kind.get(),
                name,
                member.getBegin().get().line,
                member.getEnd().get().line,
                member.toString()));
    }

    private static Optional<SourceUnit.Kind> kindOf(BodyDeclaration<?> member) {
        if (member instanceof MethodDeclaration) {
            return Optional.of(SourceUnit.Kind.METHOD);
        }
        if (member instanceof ConstructorDeclaration) {
            return Optional.of(SourceUnit.Kind.CONSTRUCTOR);
        }
        if (member instanceof InitializerDeclaration) {
            return Optional.of(SourceUnit.Kind.INITIALIZER);
        }
        if (member instanceof FieldDeclaration) {
            return Optional.of(SourceUnit.Kind.FIELD);
        }
        return Optional.empty();
    }

    /**
     * Names are qualified by their enclosing type, and methods carry their parameter types.
     * Without both, two overloads or two inner classes with a same-named method would
     * collapse into a single unit across the two revisions.
     */
    private static String nameOf(BodyDeclaration<?> member) {
        String prefix = enclosingTypes(member);
        String simple = switch (member) {
            case MethodDeclaration method -> method.getNameAsString() + parameterList(method);
            case ConstructorDeclaration constructor -> "<init>" + parameterList(constructor);
            case InitializerDeclaration initializer -> initializer.isStatic() ? "<clinit>" : "<init-block>";
            case FieldDeclaration field -> field.getVariables().stream()
                    .map(VariableDeclarator::getNameAsString)
                    .collect(Collectors.joining(","));
            default -> "";
        };
        if (simple.isEmpty()) {
            return "";
        }
        return prefix.isEmpty() ? simple : prefix + "." + simple;
    }

    private static String parameterList(CallableDeclaration<?> callable) {
        StringJoiner joiner = new StringJoiner(", ", "(", ")");
        for (Parameter parameter : callable.getParameters()) {
            joiner.add(parameter.getType().asString() + (parameter.isVarArgs() ? "..." : ""));
        }
        return joiner.toString();
    }

    private static String enclosingTypes(Node node) {
        List<String> names = new ArrayList<>();
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof TypeDeclaration<?> type) {
                names.add(0, type.getNameAsString());
            }
            parent = parent.get().getParentNode();
        }
        return String.join(".", names);
    }

    private static boolean overlaps(BodyDeclaration<?> member, int startLine, int endLine) {
        if (member.getBegin().isEmpty() || member.getEnd().isEmpty()) {
            return false;
        }
        return member.getBegin().get().line <= endLine && member.getEnd().get().line >= startLine;
    }

    private static boolean contains(BodyDeclaration<?> outer, BodyDeclaration<?> inner) {
        if (outer == inner || outer.getBegin().isEmpty() || inner.getBegin().isEmpty()) {
            return false;
        }
        return outer.getBegin().get().isBeforeOrEqual(inner.getBegin().get())
                && outer.getEnd().get().isAfterOrEqual(inner.getEnd().get());
    }

    private static int span(SourceUnit unit) {
        return unit.endLine() - unit.startLine() + 1;
    }

    /**
     * Best-effort count of the lines inside a unit that a hunk actually touched, so a
     * budget can be spent proportionally to the size of the change.
     */
    private static int coveredLines(SourceUnit unit, List<FileChange.EditRange> edits, boolean newSide) {
        if (unit == null) {
            return 0;
        }
        int covered = 0;
        for (int line = unit.startLine(); line <= unit.endLine(); line++) {
            for (FileChange.EditRange edit : edits) {
                int from = newSide ? edit.newStart() : edit.oldStart();
                int to = newSide ? edit.newEnd() : edit.oldEnd();
                if (line >= from && line <= to) {
                    covered++;
                    break;
                }
            }
        }
        return covered;
    }

    /**
     * Imports, package declarations and the type header have no enclosing member. They are
     * still worth a unit: an added import is a new dependency, and a removed annotation on
     * the class changes its contract. The raw source text is kept so a human can read it.
     */
    private static SourceUnit typeLevelRegion(String fileText, List<FileChange.EditRange> edits, boolean newSide) {
        List<int[]> ranges = new ArrayList<>();
        for (FileChange.EditRange edit : edits) {
            int from = newSide ? edit.newStart() : edit.oldStart();
            int to = newSide ? edit.newEnd() : edit.oldEnd();
            if (from <= to) {
                ranges.add(new int[]{from, to});
            }
        }
        if (ranges.isEmpty()) {
            return null;
        }
        ranges.sort(Comparator.comparingInt(range -> range[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && range[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new int[]{range[0], range[1]});
            }
        }
        List<String> lines = fileText == null ? List.of() : fileText.lines().toList();
        if (lines.isEmpty()) {
            return null;
        }
        StringJoiner text = new StringJoiner("\n");
        int startLine = Integer.MAX_VALUE;
        int endLine = 0;
        for (int[] range : merged) {
            int from = Math.max(1, Math.min(range[0], lines.size()));
            int to = Math.max(from, Math.min(range[1], lines.size()));
            startLine = Math.min(startLine, from);
            endLine = Math.max(endLine, to);
            text.add(String.join("\n", lines.subList(from - 1, to)));
        }
        return new SourceUnit(SourceUnit.Kind.TYPE_LEVEL, TYPE_LEVEL_NAME, startLine, endLine, text.toString());
    }

    private static final class Mutable {
        private final String key;
        private SourceUnit before;
        private SourceUnit after;
        private final List<FileChange.EditRange> edits = new ArrayList<>();
        private int addedLines;
        private int removedLines;

        private Mutable(String key) {
            this.key = key;
        }
    }
}
