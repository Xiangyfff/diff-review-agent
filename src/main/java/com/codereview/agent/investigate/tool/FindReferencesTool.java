package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lists the files that mention a name anywhere in their source.
 *
 * <p>This is the name-level answer, and the summary says so: matching text is not proof of a call.
 * A caller looking for "who breaks if I change this signature" still gets the useful shortlist,
 * which is exactly what the index was built for.
 */
public final class FindReferencesTool implements ReviewTool {

    private static final int MAX_FILES = 12;

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
        return "List files whose source mentions a name; name-level only, not a resolved call graph.";
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
                listing.append("... and ").append(files.size() - shown).append(" more file(s)");
                break;
            }
            listing.append(shown == 0 ? "" : "\n").append(file);
            shown++;
        }
        excerpts.add(new ToolOutcome.Excerpt("<index>", 0, 0, listing.toString()));
        return ToolOutcome.of(symbol + " is mentioned in " + files.size() + " file(s); name-level only",
                excerpts);
    }
}
