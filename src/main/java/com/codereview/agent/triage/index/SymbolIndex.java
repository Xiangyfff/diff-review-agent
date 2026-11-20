package com.codereview.agent.triage.index;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A coarse, name-level index of a repository: which files mention a symbol, and where it is
 * declared.
 *
 * <p>This is the cheap half of code intelligence. It answers "is this name used elsewhere" and
 * "is it public", which is enough to order units and to give the model a hint. It does not
 * resolve calls, distinguish overloads or follow inheritance — that needs a type resolver and
 * belongs to the investigate stage, where it is computed on demand and cited as evidence.
 *
 * <p>Immutable; built by {@link IndexBuilder}.
 */
public final class SymbolIndex {

    private final Map<String, Set<String>> filesBySymbol;
    private final Map<String, Declaration> declarations;
    private final int filesIndexed;
    private final int parseFailures;
    private final boolean truncated;

    public SymbolIndex(Map<String, Set<String>> filesBySymbol, Map<String, Declaration> declarations,
                       int filesIndexed, int parseFailures, boolean truncated) {
        this.filesBySymbol = freeze(filesBySymbol);
        this.declarations = Collections.unmodifiableMap(new LinkedHashMap<>(declarations));
        this.filesIndexed = filesIndexed;
        this.parseFailures = parseFailures;
        this.truncated = truncated;
    }

    public static SymbolIndex empty() {
        return new SymbolIndex(Map.of(), Map.of(), 0, 0, false);
    }

    public boolean isEmpty() {
        return filesBySymbol.isEmpty();
    }

    public SymbolFacts factsFor(String symbol) {
        Set<String> files = filesBySymbol.getOrDefault(symbol, Set.of());
        Declaration declaration = declarations.get(symbol);
        boolean declared = declaration != null;
        int otherFiles = files.size();
        if (declared && files.contains(declaration.file())) {
            otherFiles--;
        }
        return new SymbolFacts(symbol, files.size(), Math.max(otherFiles, 0), declared,
                declared && declaration.publiclyDeclared(), declared ? declaration.count() : 0);
    }

    public int filesIndexed() {
        return filesIndexed;
    }

    /**
     * @return where the name is declared, as far as the coarse index can tell (first site seen)
     */
    public java.util.Optional<Declaration> declaration(String symbol) {
        return java.util.Optional.ofNullable(declarations.get(symbol));
    }

    /**
     * @return every file whose source mentions the name, declaration file included
     */
    public Set<String> filesMentioning(String symbol) {
        return filesBySymbol.getOrDefault(symbol, Set.of());
    }

    public int parseFailures() {
        return parseFailures;
    }

    public boolean truncated() {
        return truncated;
    }

    public int symbolsIndexed() {
        return filesBySymbol.size();
    }

    /**
     * One declaration site. {@code count} is how many declarations share this simple name — a
     * test method and a production method can collide, and then the visibility recorded here is
     * only the first one seen, which is why {@link SymbolFacts} has to say so out loud.
     */
    public record Declaration(String symbol, String file, int line, boolean publiclyDeclared, int count) {
    }

    private static Map<String, Set<String>> freeze(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        source.forEach((symbol, files) -> copy.put(symbol, Collections.unmodifiableSet(new LinkedHashSet<>(files))));
        return Collections.unmodifiableMap(copy);
    }
}
