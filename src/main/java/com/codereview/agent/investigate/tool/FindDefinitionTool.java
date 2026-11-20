package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;
import com.codereview.agent.triage.index.SymbolIndex;

import java.util.List;
import java.util.Optional;

/**
 * Finds where a name is declared, using the coarse index built during triage.
 *
 * <p>It returns the declaration site and a few lines around it. It does not resolve overloads or
 * types — the answer says "declared here", not "this is the method you called".
 */
public final class FindDefinitionTool implements ReviewTool {

    private final RepoAccess repository;

    public FindDefinitionTool(RepoAccess repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "find_definition";
    }

    @Override
    public String purpose() {
        return "Locate where a symbol is declared in this repository, with a few lines of context.";
    }

    @Override
    public String arguments() {
        return "symbol (simple name such as processFindForm)";
    }

    @Override
    public ToolOutcome run(ToolCall call) {
        String symbol = call.argument("symbol");
        if (symbol.isEmpty()) {
            return ToolOutcome.failed("symbol is required");
        }
        SymbolIndex.Declaration declaration = repository.index().declaration(symbol).orElse(null);
        if (declaration == null) {
            return ToolOutcome.failed("no declaration of " + symbol
                    + " was found by the name index (it may come from a dependency)");
        }
        RepoAccess.Text text = repository.read(declaration.file(),
                Math.max(1, declaration.line() - 2), declaration.line() + 12);
        String summary = symbol + " is declared at " + declaration.file() + ":" + declaration.line()
                + (declaration.count() > 1 ? " (the name is declared " + declaration.count() + " times)" : "");
        return ToolOutcome.of(summary,
                List.of(new ToolOutcome.Excerpt(text.file(), text.startLine(), text.endLine(), text.text())));
    }
}
