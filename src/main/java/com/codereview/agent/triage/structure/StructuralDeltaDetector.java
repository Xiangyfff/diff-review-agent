package com.codereview.agent.triage.structure;

import com.codereview.agent.triage.parse.SourceParser;
import com.codereview.agent.triage.unit.AnalysisUnit;
import com.codereview.agent.triage.unit.SourceUnit;
import com.github.javaparser.ast.body.BodyDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns one analysis unit into the list of structural changes it contains.
 *
 * <p>This is the entry point the triage stage calls. Everything above it works with
 * {@link StructuralChange}; everything below it works with syntax trees.
 *
 * <p>Structural detection never decides whether a change is a problem. It reports shapes,
 * with the evidence attached, and stops there.
 */
public final class StructuralDeltaDetector {

    /**
     * @param changes what was detected, possibly empty
     * @param parsed  false when either revision failed to parse, which means the caller
     *                should not read an empty list as "nothing changed"
     */
    public record Detection(List<StructuralChange> changes, boolean parsed) {

        static Detection of(List<StructuralChange> changes) {
            return new Detection(List.copyOf(changes), true);
        }
    }

    private final SourceParser parser;

    public StructuralDeltaDetector(SourceParser parser) {
        this.parser = parser;
    }

    public Detection detect(AnalysisUnit unit) {
        int line = unit.startLine();
        if (unit.isAdded()) {
            return Detection.of(List.of(StructuralChange.exact(StructuralChange.Kind.UNIT_ADDED, line,
                    "new " + kindWord(unit.kind()) + " `" + unit.name() + "`",
                    "", unit.textAfter())));
        }
        if (unit.isDeleted()) {
            return Detection.of(List.of(StructuralChange.exact(StructuralChange.Kind.UNIT_REMOVED, line,
                    kindWord(unit.kind()) + " `" + unit.name() + "` deleted",
                    unit.textBefore(), "")));
        }
        if (unit.hasNoEffectiveChange()) {
            return Detection.of(List.of());
        }
        if (unit.kind() == SourceUnit.Kind.TYPE_LEVEL) {
            return Detection.of(typeLevelChange(unit, line));
        }

        Optional<BodyDeclaration<?>> before = parser.parseMember(unit.textBefore());
        Optional<BodyDeclaration<?>> after = parser.parseMember(unit.textAfter());
        if (before.isEmpty() || after.isEmpty()) {
            return new Detection(List.of(StructuralChange.heuristic(StructuralChange.Kind.UNPARSED, line,
                    "unit could not be parsed on " + (before.isEmpty() ? "the old" : "the new") + " side",
                    unit.textBefore(), unit.textAfter())), false);
        }

        List<StructuralChange> changes = new ArrayList<>();
        DeclarationDelta.compare(before.get(), after.get(), line, changes);
        BodyDelta.compare(before.get(), after.get(), line, changes);
        boolean nothingExact = changes.stream()
                .noneMatch(change -> change.confidence() == StructuralChange.Confidence.EXACT);
        if (nothingExact && Literals.onlyLiteralsDiffer(unit.textBefore(), unit.textAfter())
                && !Literals.onlyLoggingLinesDiffer(unit.textBefore(), unit.textAfter())) {
            // Nothing structured moved, but every difference sits inside a literal: a return value,
            // a constant, a message. That is a change of intent the author made on purpose or by
            // mistake, and it is invisible to every shape rule above, so say it plainly.
            changes.add(StructuralChange.exact(StructuralChange.Kind.LITERAL_CHANGED, line,
                    "only a literal changed: " + Literals.values(unit.textBefore())
                            + " -> " + Literals.values(unit.textAfter()),
                    unit.textBefore(), unit.textAfter()));
        }
        if (changes.isEmpty()) {
            // The text differs but nothing recognisable: a field initializer, an annotation
            // argument, a comment-free syntactic tweak. Report the fact, not a guess.
            changes.add(StructuralChange.heuristic(StructuralChange.Kind.STATEMENTS_ADDED, line,
                    "unit text changed without a recognised shape", unit.textBefore(), unit.textAfter()));
        }
        return Detection.of(changes);
    }

    private static List<StructuralChange> typeLevelChange(AnalysisUnit unit, int line) {
        if (unit.textBefore().replaceAll("\\s+", "").equals(unit.textAfter().replaceAll("\\s+", ""))) {
            return List.of();
        }
        return List.of(StructuralChange.heuristic(StructuralChange.Kind.TYPE_LEVEL_CHANGED, line,
                unit.addedLines() + " line(s) added and " + unit.removedLines()
                        + " removed outside any declaration: imports, class header or annotations",
                unit.textBefore(), unit.textAfter()));
    }

    private static String kindWord(SourceUnit.Kind kind) {
        return switch (kind) {
            case METHOD -> "method";
            case CONSTRUCTOR -> "constructor";
            case FIELD -> "field";
            case INITIALIZER -> "initializer";
            case TYPE_LEVEL -> "type-level region";
        };
    }
}
