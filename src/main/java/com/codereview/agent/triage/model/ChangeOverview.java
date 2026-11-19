package com.codereview.agent.triage.model;

import java.util.List;

/**
 * The whole change, compressed to what an intent summary needs.
 *
 * <p>Full file text is deliberately absent. The scan has to stay cheap: it exists to produce
 * one sentence of context and a handful of cross-file concerns, not to read the change.
 */
public record ChangeOverview(
        List<FileSummary> files,
        int addedLines,
        int removedLines,
        int unitCount) {

    public record FileSummary(
            String path,
            int addedLines,
            int removedLines,
            List<String> changedUnits,
            List<String> removedDeclarations) {

        public FileSummary {
            changedUnits = List.copyOf(changedUnits);
            removedDeclarations = List.copyOf(removedDeclarations);
        }
    }

    public ChangeOverview {
        files = List.copyOf(files);
    }
}
