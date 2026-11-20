package com.codereview.agent.investigate;

import java.util.List;

/**
 * Everything one unit's investigation needs to start: where it is, what changed, what to answer.
 *
 * <p>Deliberately flat. The loop does not need the whole triage plan, and keeping the adapter
 * outside this package means the investigate stage can be exercised with two strings and a list.
 */
public record InvestigationTask(
        String unitKey,
        String file,
        int line,
        String before,
        String after,
        List<String> changes,
        List<String> questions) {

    public InvestigationTask {
        changes = changes == null ? List.of() : List.copyOf(changes);
        questions = questions == null ? List.of() : List.copyOf(questions);
    }
}
