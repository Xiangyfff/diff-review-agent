package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lists the files that mention a name anywhere in their source, and shows the lines that do.
 *
 * <p>This is still a name-level answer: matching text is not proof of a call, and the summary says
 * so. Showing the matching lines rather than just file names matters, because a caller asking "who
 * breaks if I change this signature" needs to see how the name is used, and until now that was the
 * one thing this tool could not answer.
 */
public final class FindReferencesTool implements ReviewTool {

    private static final int MAX_FILES = 6;
    private static final int MAX_HITS_PER_FILE = 2;
    private static final int MAX_READ_LINES = 400;

    private final RepoAccess repository;

    public FindReferencesTool(RepoAccess repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "find_references";
    }

    @Override
    public String purpose() {
        return "List files that mention a name and show the matching lines; name-level only, "
                + "not a resolved call graph.";
    }

    @Override
    public String arguments() {
        return "symbol (simple name)";
    }

    @Override
    public ToolOutcome run(ToolCall call) {
        String symbol = call.argument("symbol");
        if (symbol.isEmpty()) {
            return ToolOutcome.failed("symbol is required");
        }
        Set<String> files = repository.index().filesMentioning(symbol);
        if (files.isEmpty()) {
            return ToolOutcome.failed("the index never saw " + symbol + " in any source file");
        }
        List<ToolOutcome.Excerpt> excerpts = new ArrayList<>();
        StringBuilder listing = new StringBuilder();
        int shown = 0;
        for (String file : files) {
            if (shown == MAX_FILES) {
                listing.append(shown == 0 ? "" : "\n").append("... and ")
                        .append(files.size() - shown).append(" more file(s)");
                break;
            }
            shown++;
            listing.append(shown == 1 ? "" : "\n").append(file);
            for (ToolOutcome.Excerpt hit : hitsIn(file, symbol)) {
                excerpts.add(hit);
            }
        }
        if (excerpts.isEmpty()) {
            // Still mention the files: seeing the name in a file we could not read is a lead.
            excerpts.add(new ToolOutcome.Excerpt("<index>", 0, 0, listing.toString()));
        }
        return ToolOutcome.of(symbol + " is mentioned in " + files.size() + " file(s); name-level only"
                + ", showing at most " + MAX_FILES + " of them", excerpts);
    }

    private List<ToolOutcome.Excerpt> hitsIn(String file, String symbol) {
        List<String> lines;
        try {
            lines = repository.readLines(file);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<ToolOutcome.Excerpt> excerpts = new ArrayList<>();
        for (int number = 1; number <= Math.min(lines.size(), MAX_READ_LINES); number++) {
            String line = lines.get(number - 1);
            if (!line.contains(symbol)) {
                continue;
            }
            excerpts.add(new ToolOutcome.Excerpt(file, number, number,
                    number + ": " + line.strip()));
            if (excerpts.size() == MAX_HITS_PER_FILE) {
                break;
            }
        }
        return excerpts;
    }
}
