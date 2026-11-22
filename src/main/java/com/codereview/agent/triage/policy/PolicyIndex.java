package com.codereview.agent.triage.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A brute-force vector index over the policy chunks of one repository.
 *
 * <p>No vector database, on purpose. A repository has tens to hundreds of policy sections; a full
 * scan of that many vectors costs microseconds, while a database would add a service, a schema and
 * a second failure mode to explain. The comment to make in an interview is "I evaluated a vector
 * store and the corpus does not justify one", which is only true if the code says so too.
 *
 * <p>Retrieval is deliberately conservative: a chunk has to clear the score cut to be returned, and
 * an empty hit list is a normal outcome. A wrong excerpt is worse than no excerpt, because the
 * model will happily turn it into a comment about a rule that does not apply.
 */
public final class PolicyIndex {

    private final List<PolicyChunk> chunks;
    private final float[][] vectors;
    private final Embedder embedder;
    private final int embedFailures;

    private static final double LEXICAL_WEIGHT = 0.25;
    private static final java.util.regex.Pattern IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");
    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "public", "private", "protected", "return", "string", "void", "class", "static",
            "final", "import", "package", "this", "that", "with", "from", "statement", "statements",
            "changed", "guard", "code", "file", "true", "false", "null");

    private PolicyIndex(List<PolicyChunk> chunks, float[][] vectors, Embedder embedder, int embedFailures) {
        this.chunks = List.copyOf(chunks);
        this.vectors = vectors;
        this.embedder = embedder;
        this.embedFailures = embedFailures;
    }

    public static PolicyIndex empty() {
        return new PolicyIndex(List.of(), new float[0][], null, 0);
    }

    /**
     * Embeds every chunk once, at start-up. A chunk whose embedding fails is dropped and counted
     * rather than indexed with a zero vector, because a zero vector would silently never match.
     */
    public static PolicyIndex build(PolicyLibrary library, Embedder embedder) {
        if (library == null || library.isEmpty()) {
            return empty();
        }
        List<PolicyChunk> kept = new ArrayList<>();
        List<float[]> keptVectors = new ArrayList<>();
        int failures = 0;
        for (PolicyChunk chunk : library.chunks()) {
            try {
                float[] vector = embedder.embed(chunk.heading() + "\n" + chunk.text());
                if (vector == null || vector.length == 0) {
                    failures++;
                    continue;
                }
                kept.add(chunk);
                keptVectors.add(normalise(vector));
            } catch (RuntimeException failure) {
                failures++;
            }
        }
        return new PolicyIndex(kept, keptVectors.toArray(new float[0][]), embedder, failures);
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public int size() {
        return chunks.size();
    }

    public int embedFailures() {
        return embedFailures;
    }

    public List<PolicyChunk> chunks() {
        return chunks;
    }

    /**
     * @param topK     at most this many hits
     * @param minScore cosine similarity below which a chunk is not returned at all
     */
    public List<PolicyHit> search(String query, int topK, double minScore) {
        if (isEmpty() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        float[] probe;
        try {
            probe = normalise(embedder.embed(query));
        } catch (RuntimeException unreachable) {
            return List.of();
        }
        if (probe.length == 0 || probe.length != vectors[0].length) {
            return List.of();
        }
        java.util.Set<String> queryTerms = terms(query);
        List<PolicyHit> hits = new ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            double score = dot(probe, vectors[i]);
            if (!queryTerms.isEmpty()) {
                score += LEXICAL_WEIGHT * overlap(queryTerms, chunks.get(i));
            }
            if (score >= minScore) {
                hits.add(new PolicyHit(chunks.get(i), score));
            }
        }
        hits.sort(Comparator.comparingDouble(PolicyHit::score).reversed());
        return hits.size() <= topK ? hits : hits.subList(0, topK);
    }

    /** The mandatory chunks among the hits; these are what gate the tier. */
    public static List<PolicyHit> mandatory(List<PolicyHit> hits) {
        return hits.stream().filter(hit -> hit.chunk().mandatory()).toList();
    }

    /** Identifier-shaped terms in a text, lower-cased and minus the words every method signature has. */
    private static java.util.Set<String> terms(String text) {
        java.util.Set<String> terms = new java.util.HashSet<>();
        java.util.regex.Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String term = matcher.group().toLowerCase(java.util.Locale.ROOT);
            if (!STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    /** Share of the query's identifiers that appear verbatim in the chunk. */
    private static double overlap(java.util.Set<String> queryTerms, PolicyChunk chunk) {
        java.util.Set<String> chunkTerms = terms(chunk.heading() + " " + chunk.text());
        if (chunkTerms.isEmpty()) {
            return 0;
        }
        long matched = queryTerms.stream().filter(chunkTerms::contains).count();
        return (double) matched / queryTerms.size();
    }

    private static float[] normalise(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            return new float[0];
        }
        double length = Math.sqrt(norm);
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / length);
        }
        return out;
    }

    private static double dot(float[] left, float[] right) {
        double sum = 0;
        for (int i = 0; i < left.length; i++) {
            sum += left[i] * right[i];
        }
        return sum;
    }
}
