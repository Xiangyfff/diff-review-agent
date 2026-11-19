package com.codereview.agent.triage.rules;

/**
 * What kind of risk a unit touches, used for ordering rather than for scoring.
 *
 * <p>The order of the constants <em>is</em> the priority order. A single ordinal beats a
 * computed severity score here for one reason: scores from a model are not calibrated and
 * drift between runs, while "a change to authentication outranks a change to internal
 * formatting" is a statement a reviewer would agree with every time.
 */
public enum RiskCategory {

    /** Authentication, authorisation, crypto, payment, SQL construction. */
    SECURITY,

    /** Locks, volatiles, executors, shared mutable state. */
    CONCURRENCY,

    /** Sockets, streams, pools, anything that must be closed. */
    RESOURCE,

    /** Transactions, null contracts, swallowed failures, data written twice. */
    CONSISTENCY,

    /** Public signatures, visibility, thrown exceptions, removed declarations. */
    CONTRACT,

    /** Local branching and control flow with no external effect. */
    LOGIC,

    /** Comments, documentation, configuration values. */
    DOCS;

    public boolean outranks(RiskCategory other) {
        return ordinal() < other.ordinal();
    }
}
