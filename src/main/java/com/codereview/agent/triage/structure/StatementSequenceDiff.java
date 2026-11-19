package com.codereview.agent.triage.structure;

import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Aligns the statement sequences of two versions of a member and reports the gaps.
 *
 * <p>Statement-level longest-common-subsequence, not tree diff. Statements are the right
 * granularity: a field-level diff is unreadable, a whole-tree diff needs node identities
 * that do not survive an insertion, and statements already carry the structure a reviewer
 * reasons about. Kept statements are dropped from the output; only the gaps remain, which
 * is exactly the "what was deleted, what was added" answer the next stage needs.
 */
public final class StatementSequenceDiff {

    /** A contiguous gap: the statements removed from one revision and added to the other. */
    public record Block(List<Statement> removed, List<Statement> added) {

        public Block {
            removed = List.copyOf(removed);
            added = List.copyOf(added);
        }

        public boolean isEmpty() {
            return removed.isEmpty() && added.isEmpty();
        }

        public int delta() {
            return removed.size() + added.size();
        }
    }

    /** Beyond this the quadratic table is not worth the memory; fall back to one big gap. */
    private static final long MAX_CELLS = 4_000_000L;

    private StatementSequenceDiff() {
    }

    public static List<Block> diff(List<Statement> before, List<Statement> after, Set<String> localNames) {
        int n = before.size();
        int m = after.size();
        if ((long) n * m > MAX_CELLS) {
            return List.of(new Block(before, after));
        }

        List<String> left = before.stream().map(statement -> StatementNormalizer.normalize(statement, localNames)).toList();
        List<String> right = after.stream().map(statement -> StatementNormalizer.normalize(statement, localNames)).toList();

        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = left.get(i).equals(right.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<Block> blocks = new ArrayList<>();
        // On a tie prefer taking the added statement first: it keeps the alignment on the
        // rare common statement instead of deleting everything up to the next match.
        List<Statement> removed = new ArrayList<>();
        List<Statement> added = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && left.get(i).equals(right.get(j))) {
                flush(blocks, removed, added);
                i++;
                j++;
            } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                added.add(after.get(j));
                j++;
            } else {
                removed.add(before.get(i));
                i++;
            }
        }
        flush(blocks, removed, added);
        return blocks;
    }

    private static void flush(List<Block> blocks, List<Statement> removed, List<Statement> added) {
        if (!removed.isEmpty() || !added.isEmpty()) {
            blocks.add(new Block(removed, added));
            removed.clear();
            added.clear();
        }
    }
}
