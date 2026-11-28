package com.codereview.agent.triage.model;

/**
 * The reason a model gives for not investigating a unit, in a form the program can check.
 *
 * <p>A free-text abstain reason cannot decide anything: "only a log line changed" is either true,
 * in which case the checker can see it in the structural changes, or it is the model's impression,
 * in which case it must not be allowed to remove a unit from review. The witness is the small part
 * of the reason that is mechanical.
 *
 * @param kind  one of {@code test-only} or {@code log-only}; empty when the model has no witness
 * @param quote a line from the unit that shows the claim, for {@code log-only}
 */
public record Witness(String kind, String quote) {

    public static final String TEST_ONLY = "test-only";
    public static final String LOG_ONLY = "log-only";
    public static final Witness NONE = new Witness("", "");

    public Witness {
        kind = kind == null ? "" : kind.strip().toLowerCase(java.util.Locale.ROOT);
        quote = quote == null ? "" : quote.strip();
    }

    public boolean present() {
        return !kind.isEmpty();
    }

    public String describe() {
        if (!present()) {
            return "";
        }
        return quote.isEmpty() ? kind : kind + ": " + quote;
    }
}
