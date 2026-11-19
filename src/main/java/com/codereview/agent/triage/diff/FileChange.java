package com.codereview.agent.triage.diff;

import java.util.List;

/**
 * One file touched by a change, carrying both the before and the after revision.
 *
 * <p>Text is kept for the whole file rather than per hunk on purpose: locating a changed
 * line inside its enclosing method needs the complete syntax tree, and re-parsing a file
 * is cheap compared to a single model call.
 */
public record FileChange(
        String oldPath,
        String newPath,
        ChangeType type,
        List<EditRange> edits,
        String oldText,
        String newText) {

    public enum ChangeType { ADDED, MODIFIED, DELETED, RENAMED, COPIED }

    public FileChange {
        edits = List.copyOf(edits);
    }

    /** The path a human would expect to see in a review comment. */
    public String displayPath() {
        return newPath != null ? newPath : oldPath;
    }

    public boolean isJavaSource() {
        String path = displayPath();
        return path != null && path.endsWith(".java");
    }

    /**
     * A contiguous region that differs between the two revisions.
     *
     * <p>Lines are 1-based and inclusive, which is the coordinate system JavaParser uses
     * for node ranges. A pure insertion therefore has an empty old range
     * ({@code oldStart == oldEnd + 1}) and a pure deletion an empty new range. Making that
     * convention explicit here keeps the conversion from JGit's 0-based half-open edits in
     * exactly one place.
     */
    public record EditRange(int oldStart, int oldEnd, int newStart, int newEnd) {

        /** JGit reports 0-based half-open offsets; convert once, at the boundary. */
        public static EditRange fromZeroBasedHalfOpen(int beginOld, int endOld, int beginNew, int endNew) {
            int oldStart = beginOld + 1;
            int oldEnd = endOld;              // exclusive 0-based == inclusive 1-based
            int newStart = beginNew + 1;
            int newEnd = endNew;
            return new EditRange(oldStart, oldEnd, newStart, newEnd);
        }

        public boolean hasOldLines() {
            return oldStart <= oldEnd;
        }

        public boolean hasNewLines() {
            return newStart <= newEnd;
        }

        public int removedLines() {
            return hasOldLines() ? oldEnd - oldStart + 1 : 0;
        }

        public int addedLines() {
            return hasNewLines() ? newEnd - newStart + 1 : 0;
        }

        @Override
        public String toString() {
            return "old[" + oldStart + ".." + oldEnd + "] new[" + newStart + ".." + newEnd + "]";
        }
    }
}
