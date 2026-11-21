package com.codereview.agent.triage.structure;

/**
 * A structural difference between two revisions of one declaration.
 *
 * <p>A change is evidence, not a verdict. The record deliberately carries the before and
 * after snippets next to the classification: a reviewer (human or model) judges severity far
 * better from "this guard disappeared" than from the sentence "a null check was removed",
 * and the same snippets are what the verification stage later has to be able to point at.
 */
public record StructuralChange(
        Kind kind,
        Confidence confidence,
        String detail,
        String beforeSnippet,
        String afterSnippet,
        int line) {

    public enum Kind {
        UNIT_ADDED,
        UNIT_REMOVED,
        SIGNATURE_CHANGED,
        VISIBILITY_CHANGED,
        MODIFIER_CHANGED,
        THROWS_CHANGED,
        ANNOTATION_REMOVED,
        ANNOTATION_ADDED,
        NULL_CHECK_REMOVED,
        CONDITION_INVERTED,
        SWALLOWED_EXCEPTION,
        RESOURCE_UNCLOSED,
        EARLY_EXIT_REMOVED,
        STATEMENTS_DELETED,
        OPERATOR_CHANGED,
    /** A number or string literal changed while the statement kept its shape. */
    LITERAL_CHANGED,
    /** A call gained a null literal argument. */
    NULL_ARGUMENT_ADDED,
        STATEMENTS_REORDERED,
        STATEMENTS_ADDED,
        /** Changed outside any declaration: imports, class header, annotations on the type. */
        TYPE_LEVEL_CHANGED,
        /** The region could not be parsed, so no shape detection was possible. */
        UNPARSED
    }

    /**
     * EXACT means the shape itself is certain: the syntax tree says a guard is gone.
     * HEURISTIC means the shape was guessed from names or context, and a false positive is
     * possible; those findings must not gate anything on their own.
     */
    public enum Confidence { EXACT, HEURISTIC }

    private static final int MAX_SNIPPET = 240;

    public StructuralChange {
        beforeSnippet = truncate(beforeSnippet);
        afterSnippet = truncate(afterSnippet);
    }

    public static StructuralChange exact(Kind kind, int line, String detail, String before, String after) {
        return new StructuralChange(kind, Confidence.EXACT, detail, before, after, line);
    }

    public static StructuralChange heuristic(Kind kind, int line, String detail, String before, String after) {
        return new StructuralChange(kind, Confidence.HEURISTIC, detail, before, after, line);
    }

    public String describe() {
        String snippet = afterSnippet == null || afterSnippet.isBlank() ? beforeSnippet : afterSnippet;
        return kind.name() + " at L" + line + ": " + detail
                + (snippet == null || snippet.isBlank() ? "" : " | " + snippet);
    }

    private static String truncate(String snippet) {
        if (snippet == null) {
            return "";
        }
        String collapsed = snippet.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= MAX_SNIPPET ? collapsed : collapsed.substring(0, MAX_SNIPPET) + "...";
    }
}
