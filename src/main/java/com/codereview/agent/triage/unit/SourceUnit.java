package com.codereview.agent.triage.unit;

/**
 * A named declaration inside a Java file: a method, a constructor, a field, an
 * initializer block, or the type-level remainder (package, imports, type header).
 *
 * <p>The function is the unit of analysis. That choice is not cosmetic: static analysis
 * findings, test coverage and call-graph edges are all already expressed per method, so
 * picking the method as the unit means every external signal maps onto it without any
 * translation table.
 */
public record SourceUnit(Kind kind, String name, int startLine, int endLine, String text) {

    public enum Kind { METHOD, CONSTRUCTOR, FIELD, INITIALIZER, TYPE_LEVEL }

    public SourceUnit {
        if (startLine > endLine) {
            throw new IllegalArgumentException("startLine " + startLine + " > endLine " + endLine);
        }
    }

    /** Stable identity used to pair the same declaration across the two revisions. */
    public String key() {
        return kind.name() + "#" + name;
    }

    public String displayName() {
        return name + " (L" + startLine + "-" + endLine + ")";
    }

    public boolean isFunction() {
        return kind == Kind.METHOD || kind == Kind.CONSTRUCTOR || kind == Kind.INITIALIZER;
    }
}
