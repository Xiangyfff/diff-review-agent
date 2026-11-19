package com.codereview.agent.triage.structure;

import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reduces a statement to a comparable shape.
 *
 * <p>Two normalisations matter. Whitespace and formatting disappear for free because the
 * input is a syntax tree rather than text. Local names are then replaced by positional
 * placeholders: renaming a variable is a very common no-op edit, and without this step a
 * rename would show up as "seven statements deleted, seven added" and drown every real
 * finding.
 *
 * <p>The renaming map is scope-blind on purpose. It only rewrites names declared inside the
 * member being compared, so a field that happens to share a local's name can still be
 * rewritten by mistake. That trade is deliberate: the alternative is a resolver, which the
 * triage stage explicitly does not have.
 */
public final class StatementNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private StatementNormalizer() {
    }

    public static String normalize(Statement statement) {
        return normalize(statement, Set.of());
    }

    public static String normalize(Statement statement, Set<String> localNames) {
        String text = WHITESPACE.matcher(statement.toString()).replaceAll(" ").trim();
        return localNames.isEmpty() ? text : canonicalize(text, localNames);
    }

    static String canonicalize(String text, Set<String> localNames) {
        List<String> sorted = new ArrayList<>(localNames);
        sorted.sort(Comparator.naturalOrder());
        Map<String, String> placeholders = new LinkedHashMap<>();
        for (int index = 0; index < sorted.size(); index++) {
            placeholders.put(sorted.get(index), "$v" + index);
        }
        // Longest alternative first so that "value" is not matched as "va" + "lue".
        String alternatives = sorted.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        if (alternatives.isEmpty()) {
            return text;
        }
        Matcher matcher = Pattern.compile("\\b(" + alternatives + ")\\b").matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(placeholders.get(matcher.group(1))));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
