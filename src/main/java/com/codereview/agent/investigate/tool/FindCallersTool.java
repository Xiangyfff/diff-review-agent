package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;
import com.codereview.agent.investigate.graph.CallGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the call sites that resolve to one declaration, using type information rather than names.
 *
 * <p>The difference from {@code find_references} is the point of this tool: a same-named method on
 * another class, an unrelated overload, or an interface implemented elsewhere are all excluded here
 * and all included there. It is slower, so it exists for the question that needs the accuracy.
 */
public final class FindCallersTool implements ReviewTool {

    private final RepoAccess repository;
    private final CallGraph callGraph;

    public FindCallersTool(RepoAccess repository) {
        this.repository = repository;
        this.callGraph = new CallGraph(repository.root());
    }

    @Override
    public String name() {
        return "find_callers";
    }

    @Override
    public String purpose() {
        return "List the calls that actually resolve to this method, using type resolution rather "
                + "than name matching. Slower than find_references and more precise.";
    }

    @Override
    public String arguments() {
        return "file (repository-relative), symbol (method name), line (a line inside the method)";
    }

    @Override
    public ToolOutcome run(ToolCall call) {
        String file = call.argument("file");
        String symbol = call.argument("symbol");
        int line = call.intArgument("line", 0);
        if (file.isEmpty() || symbol.isEmpty()) {
            return ToolOutcome.failed("file and symbol are required");
        }
        if (repository.resolve(file).isEmpty()) {
            return ToolOutcome.failed(file + " is not a file inside the repository");
        }
        List<CallGraph.Call> callers = callGraph.callersOf(file, line, symbol);
        if (callers.isEmpty()) {
            return ToolOutcome.failed("no call site resolves to " + symbol + " at " + file
                    + ":" + line + " (unresolved calls are not counted)");
        }
        List<ToolOutcome.Excerpt> excerpts = new ArrayList<>();
        for (CallGraph.Call caller : callers) {
            excerpts.add(new ToolOutcome.Excerpt(caller.file(), caller.line(), caller.line(),
                    caller.line() + ": " + caller.snippet()));
        }
        return ToolOutcome.of(callers.size() + " call site(s) resolve to " + symbol, excerpts);
    }
}
