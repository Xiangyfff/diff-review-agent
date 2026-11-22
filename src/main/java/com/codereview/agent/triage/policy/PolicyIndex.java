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
    /** One vector per chunk prose. */
    private final float[][] proseVectors;
    /** One vector per sample code block, aligned with {@link #chunks}; empty when unused. */
    private final float[][][] sampleVectors;
    private final Embedder embedder;
    private final int embedFailures;
    private final Scoring scoring;

    private static final double LEXICAL_WEIGHT = 0.25;
    private static final java.util.regex.Pattern IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");
    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "public", "private", "protected", "return", "string", "void", "class", "static",
            "final", "import", "package", "this", "that", "with", "from", "statement", "statements",
            "changed", "guard", "code", "file", "true", "false", "null");

    /**
     * How a chunk is scored. Two modes exist so the eval can measure what the lexical bonus is
     * worth: an interview answer about the bonus should cite two rows, not a feeling.
     */
    public enum Scoring {
        /** Prose only, no lexical help: the baseline worth quoting. */
        EMBEDDING_ONLY,
        /** Prose plus the identifier-overlap bonus. */
        LEXICAL,
        /** Prose plus sample code blocks, plus the identifier bonus. */
        SAMPLES
    }

    private PolicyIndex(List<PolicyChunk> chunks, float[][] proseVectors, float[][][] sampleVectors,
                        Embedder embedder, int embedFailures, Scoring scoring) {
        this.chunks = List.copyOf(chunks);
        this.proseVectors = proseVectors;
        this.sampleVectors = sampleVectors;
        this.embedder = embedder;
        this.embedFailures = embedFailures;
        this.scoring = scoring;
    }

    public static PolicyIndex empty() {
        return new PolicyIndex(List.of(), new float[0][], new float[0][][], null, 0, Scoring.LEXICAL);
    }

    /**
     * Embeds every chunk once, at start-up. A chunk whose embedding fails is dropped and counted
     * rather than indexed with a zero vector, because a zero vector would silently never match.
     */
    public static PolicyIndex build(PolicyLibrary library, Embedder embedder) {
        return build(library, embedder, Scoring.LEXICAL);
    }

    public static PolicyIndex build(PolicyLibrary library, Embedder embedder, Scoring scoring) {
        if (library == null || library.isEmpty()) {
            return empty();
        }
        List<PolicyChunk> kept = new ArrayList<>();
        List<float[]> keptProse = new ArrayList<>();
        List<float[][]> keptSamples = new ArrayList<>();
        int failures = 0;
        for (PolicyChunk chunk : library.chunks()) {
            try {
                List<float[]> samples = new ArrayList<>();
                float[] prose = embedder.embed(chunk.heading() + "\n" + chunk.text());
                if (prose == null || prose.length == 0) {
                    failures++;
                    continue;
                }
                if (scoring == Scoring.SAMPLES) {
                    for (String sample : chunk.examples()) {
                        float[] vector = embedder.embed(codeOnly(sample));
                        if (vector != null && vector.length == prose.length) {
                            samples.add(normalise(vector));
                        }
                    }
                }
                kept.add(chunk);
                keptProse.add(normalise(prose));
                keptSamples.add(samples.toArray(new float[0][]));
            } catch (RuntimeException failure) {
                failures++;
            }
        }
        return new PolicyIndex(kept, keptProse.toArray(new float[0][]),
                keptSamples.toArray(new float[0][][]), embedder, failures, scoring);
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

    public Scoring scoring() {
        return scoring;
    }

    /**
     * The score cut for this index. Kept as a method because the eval compares scoring modes and a
     * cut written into the caller would silently apply one mode's number to another.
     */
    public double scoreCut() {
        return 0.55;
    }

    public List<PolicyChunk> chunks() {
        return chunks;
    }

    /**
     * @param topK     at most this many hits
     * @param minScore cosine similarity below which a chunk is not returned at all
     */
    public List<PolicyHit> search(String query, int topK, double minScore) {
        return search(query, query, topK, minScore);
    }

    /**
     * Retrieval with the query split into its own two parts.
     *
     * <p>The document has prose and samples; so does the query. Prose is compared with prose and
     * code with code. Matching a code change against prose was the original mistake: three password
     * comparisons were retrieved as the neighbouring clause about authentication logs, because both
     * mention passwords in prose.
     */
    public List<PolicyHit> search(String proseQuery, String codeQuery, int topK, double minScore) {
        String query = proseQuery == null || proseQuery.isBlank() ? codeQuery : proseQuery;
        if (isEmpty() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        float[] probe;
        float[] codeProbe = null;
        try {
            probe = normalise(embedder.embed(query));
            if (scoring == Scoring.SAMPLES && codeQuery != null && !codeQuery.isBlank()
                    && !codeQuery.equals(query)) {
                codeProbe = normalise(embedder.embed(codeQuery));
            }
        } catch (RuntimeException unreachable) {
            return List.of();
        }
        if (probe.length == 0 || probe.length != proseVectors[0].length) {
            return List.of();
        }
        if (codeProbe != null && codeProbe.length != probe.length) {
            codeProbe = null;
        }
        java.util.Set<String> queryTerms = terms(query);
        List<PolicyHit> hits = new ArrayList<>();
        for (int i = 0; i < proseVectors.length; i++) {
            double proseScore = dot(probe, proseVectors[i]);
            double score = proseScore;
            if (codeProbe != null && sampleVectors[i].length > 0) {
                double codeScore = -1;
                for (float[] sample : sampleVectors[i]) {
                    codeScore = Math.max(codeScore, dot(codeProbe, sample));
                }
                // A clause with samples is scored on its samples: the change is code, and the prose
                // a clause shares with its neighbours is exactly what confused the ranking.
                score = codeScore;
            }
            if (scoring != Scoring.EMBEDDING_ONLY && !queryTerms.isEmpty()) {
                score += LEXICAL_WEIGHT * overlap(queryTerms, chunks.get(i));
            }
            if (score >= minScore) {
                hits.add(new PolicyHit(chunks.get(i), score));
            }
        }
        hits.sort(Comparator.comparingDouble(PolicyHit::score).reversed());
        return hits.size() <= topK ? hits : hits.subList(0, topK);
    }

    /**
     * Per-chunk scores for a query, for the eval probe only: ranking a miss needs the numbers, not
     * a guess. The pipeline never calls this.
     */
    public java.util.List<String> explainScores(String proseQuery, String codeQuery) {
        if (isEmpty()) {
            return java.util.List.of();
        }
        float[] proseProbe = normalise(embedder.embed(proseQuery));
        float[] codeProbe = codeQuery == null || codeQuery.isBlank()
                ? null : normalise(embedder.embed(codeQuery));
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < proseVectors.length; i++) {
            double proseScore = dot(proseProbe, proseVectors[i]);
            double codeScore = -1;
            for (float[] sample : sampleVectors[i]) {
                codeScore = Math.max(codeScore, dot(codeProbe, sample));
            }
            lines.add(String.format("%s prose=%.3f code=%.3f samples=%d", chunks.get(i).id(),
                    proseScore, codeScore, sampleVectors[i].length));
        }
        return lines;
    }

    /** The mandatory chunks among the hits; these are what gate the tier. */
    public static List<PolicyHit> mandatory(List<PolicyHit> hits) {
        return hits.stream().filter(hit -> hit.chunk().mandatory()).toList();
    }

    /**
     * A sample with its comment lines removed. The comments in a policy document are written in
     * prose about the rule, so embedding them drags the sample back into prose space — which is
     * exactly the cross-modal confusion the samples exist to avoid.
     */
    private static String codeOnly(String sample) {
        StringBuilder code = new StringBuilder();
        for (String line : sample.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("//") || stripped.startsWith("/*") || stripped.startsWith("*")
                    || stripped.startsWith("*/")) {
                continue;
            }
            code.append(line).append('\n');
        }
        String result = code.toString().strip();
        return result.isEmpty() ? sample : result;
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
