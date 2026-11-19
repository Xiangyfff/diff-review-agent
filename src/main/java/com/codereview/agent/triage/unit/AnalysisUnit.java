package com.codereview.agent.triage.unit;

import com.codereview.agent.triage.diff.FileChange;

import java.util.List;

/**
 * One unit of work for the triage stage: the same declaration seen on both sides of the
 * change, plus the hunks that touched it.
 *
 * <p>Several hunks that land in the same method collapse into a single unit on purpose.
 * Two edits inside one method usually explain each other, so splitting them would make the
 * model reason about half a change.
 */
public record AnalysisUnit(
        String file,
        String key,
        SourceUnit before,
        SourceUnit after,
        List<FileChange.EditRange> edits,
        int addedLines,
        int removedLines) {

    public AnalysisUnit {
        edits = List.copyOf(edits);
        if (before == null && after == null) {
            throw new IllegalArgumentException("a unit needs at least one revision");
        }
    }

    public SourceUnit.Kind kind() {
        return present().kind();
    }

    public String name() {
        return present().name();
    }

    public int startLine() {
        return present().startLine();
    }

    public int endLine() {
        return present().endLine();
    }

    public String textBefore() {
        return before == null ? "" : before.text();
    }

    public String textAfter() {
        return after == null ? "" : after.text();
    }

    /** A wrong syntax tree on either side can make the two texts look identical. */
    public boolean hasNoEffectiveChange() {
        return before != null && after != null && before.text().equals(after.text());
    }

    public boolean isAdded() {
        return before == null;
    }

    public boolean isDeleted() {
        return after == null;
    }

    private SourceUnit present() {
        return after != null ? after : before;
    }
}
