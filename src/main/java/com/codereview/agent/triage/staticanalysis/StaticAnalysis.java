package com.codereview.agent.triage.staticanalysis;

import com.codereview.agent.triage.diff.FileChange;

import java.util.List;

/**
 * The second fact source triage labels units with: defects of the new code, not of the change.
 *
 * <p>A real analyzer needs a compiled target and a baseline snapshot; this interface is what such
 * an analyzer would implement. Which one is plugged in does not change the contract: only warnings
 * that land on lines this change added are returned.
 */
public interface StaticAnalysis {

    /** Warnings for a whole file text, unfiltered by line. */
    List<StaticWarning> analyse(String path, String text);

    /**
     * Warnings restricted to the lines the change added.
     *
     * <p>The new-side ranges are exactly the lines a reviewer would have to read; anything outside
     * them predates this commit and must not be reported here.
     */
    default List<StaticWarning> onAddedLines(FileChange change) {
        String text = change.newText();
        if (text == null || change.edits().isEmpty()) {
            return List.of();
        }
        List<StaticWarning> all = analyse(change.displayPath(), text);
        if (all.isEmpty()) {
            return List.of();
        }
        return all.stream()
                .filter(warning -> change.edits().stream().anyMatch(edit ->
                        warning.line() >= edit.newStart() && warning.line() <= edit.newEnd()))
                .toList();
    }
}
