package com.codereview.agent.investigate;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic stand-in for the real model, used by tests and by the offline CLI.
 *
 * <p>Its policy is deliberately simple and honest: read the unit's neighbourhood once, then state
 * what the first line of that evidence says, citing the evidence it just read. If the read comes
 * back empty it abandons the question with a reason. It is not a good reviewer — it exists so the
 * loop, the tools, the store and the verifier can be exercised without a network call.
 */
public final class MockInvestigationModel implements InvestigationModel {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");

    @Override
    public ModelTurn next(InvestigationState state) {
        Question question = state.open().get(0);
        if (state.evidence().isEmpty()) {
            ToolCall call = new ToolCall("read_lines", Map.of(
                    "path", state.file(),
                    "start", String.valueOf(Math.max(1, state.line() - 3)),
                    "end", String.valueOf(state.line() + 12)));
            return new ModelTurn.CallTool(question.id(), call);
        }
        Evidence evidence = state.evidence().get(0);
        String symbol = firstIdentifier(evidence.text());
        if (symbol == null || evidence.endLine() == 0) {
            return new ModelTurn.Abandon(question.id(), "读到的证据里没有可引用的代码");
        }
        String statement = "改动附近的代码仍然直接使用了 `" + symbol + "`，需要确认它在调用链上的取值来源。";
        return new ModelTurn.Conclude(question.id(), statement, List.of(evidence.id()), evidence.startLine());
    }

    private static String firstIdentifier(String text) {
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!candidate.equals("this") && !candidate.equals("return") && !candidate.equals("import")) {
                return candidate;
            }
        }
        return null;
    }
}
