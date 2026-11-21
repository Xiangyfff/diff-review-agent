package com.codereview.agent.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * How many tokens the models actually consumed, counted where the responses arrive.
 *
 * <p>Kept as process-wide counters rather than passed through every call: the number is about the
 * run, not about a unit, and the alternative is threading a mutable ledger through the triage,
 * investigation and verification APIs for a statistic. Reset it at the start of a measured run.
 */
public final class TokenLedger {

    private static final AtomicLong INPUT = new AtomicLong();
    private static final AtomicLong OUTPUT = new AtomicLong();
    private static final AtomicLong CALLS = new AtomicLong();

    private TokenLedger() {
    }

    public static void record(Integer inputTokens, Integer outputTokens) {
        CALLS.incrementAndGet();
        if (inputTokens != null) {
            INPUT.addAndGet(inputTokens);
        }
        if (outputTokens != null) {
            OUTPUT.addAndGet(outputTokens);
        }
    }

    public static void reset() {
        INPUT.set(0);
        OUTPUT.set(0);
        CALLS.set(0);
    }

    public static Snapshot snapshot() {
        return new Snapshot(CALLS.get(), INPUT.get(), OUTPUT.get());
    }

    /** A reading of the ledger: calls made, tokens in, tokens out. */
    public record Snapshot(long calls, long inputTokens, long outputTokens) {

        public long totalTokens() {
            return inputTokens + outputTokens;
        }

        public String describe() {
            return calls + " calls, " + inputTokens + " in / " + outputTokens + " out";
        }
    }
}
