package com.codereview.agent.investigate;

import java.util.List;

/**
 * A conclusion that survived verification: a statement about one place, backed by evidence ids.
 *
 * <p>Every finding also carries a demand, because "the signature changed" is not a review comment.
 * Either something has to be fixed, or the author has to confirm something; a restatement of the
 * diff is neither and should never have been concluded.
 */
public record Finding(String unitKey, String file, int line, String statement, List<String> evidenceIds,
        String demand) {

    public static final String FIX = "fix";
    public static final String CONFIRM = "confirm";

    public Finding {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        demand = demand == null ? FIX : demand.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** Kept for the offline model and older tests, which always ask for a fix. */
    public Finding(String unitKey, String file, int line, String statement, List<String> evidenceIds) {
        this(unitKey, file, line, statement, evidenceIds, FIX);
    }

    public boolean asksForConfirmation() {
        return CONFIRM.equals(demand);
    }

    public String describe() {
        return file + ":" + line + " — " + statement + " [" + String.join(", ", evidenceIds) + "]";
    }
}
