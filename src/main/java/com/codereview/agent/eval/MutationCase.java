package com.codereview.agent.eval;

/**
 * One mutation to apply on top of the previous state of the corpus.
 *
 * @param id       stable identifier, used as the commit message and in the report
 * @param path     repository-relative file to edit
 * @param search   exact text that must appear once and only once
 * @param replace  what it becomes
 * @param anchor   a substring of the mutated text, used to locate the line the defect sits on
 * @param defective whether this change introduces a known defect; false marks a clean change that
 *                  should not be reported at all
 * @param kind     the defect family, for per-family reporting
 */
public record MutationCase(String id, String path, String search, String replace, String anchor,
        boolean defective, String kind) {

    public MutationCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a case needs an id");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("a case needs a path");
        }
        if (search == null || search.isEmpty()) {
            throw new IllegalArgumentException("a case needs something to replace");
        }
        if (replace == null || replace.equals(search)) {
            throw new IllegalArgumentException("a case must actually change something");
        }
    }
}
