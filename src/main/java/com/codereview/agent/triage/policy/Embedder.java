package com.codereview.agent.triage.policy;

/**
 * Turns text into a vector. The only part of the policy feature that needs a model, and the only
 * part a test ever replaces.
 *
 * <p>Kept as an interface for two reasons: the pipeline must run offline with fixtures, and the
 * model behind it will change (local today, an API tomorrow) without touching retrieval logic.
 */
@FunctionalInterface
public interface Embedder {

    /** @return a vector; an implementation may throw if the endpoint is unreachable */
    float[] embed(String text);

    /** A fixed-width vector, useful in tests and when a document must be indexed without a model. */
    static Embedder constant(float[] vector) {
        float[] copy = vector.clone();
        return text -> copy.clone();
    }
}
