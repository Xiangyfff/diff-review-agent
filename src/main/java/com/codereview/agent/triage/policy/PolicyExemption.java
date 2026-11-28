package com.codereview.agent.triage.policy;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A clause that explicitly allows a change the rules would otherwise gate.
 *
 * <p>This is the only way a policy can lower a tier. Retrieval similarity is never enough: a
 * clause can be close to a change and still mean the opposite, so the document has to say which
 * rule it exempts, which paths it covers, and show an example. Matching all three is the
 * difference between "the document allows this" and "the model found something similar".
 *
 * <p>The result is recorded, not swallowed: an exempted unit keeps its clause id and reason in
 * the plan, is counted separately from plain skips, and never produces a comment.
 */
public record PolicyExemption(String clauseId, double score, List<String> rules) {

    public PolicyExemption {
        rules = List.copyOf(rules);
    }

    public boolean exempts(String ruleId) {
        if (ruleId == null) {
            return false;
        }
        return rules.stream().anyMatch(rule -> rule.equalsIgnoreCase(ruleId.strip()));
    }

    public String describe() {
        return clauseId + " allows " + String.join(", ", rules);
    }

    /**
     * The best allowance hit that covers this path, or {@code null}.
     *
     * @param path     the unit's file, always with {@code /} separators
     * @param hits     the retrieval hits for the unit; an allowance clause with samples is scored
     *                 on code against its examples, which is why the score is meaningful here
     * @param minScore the same cut the rest of the stage uses
     */
    public static PolicyExemption find(String path, List<PolicyHit> hits, double minScore) {
        PolicyExemption best = null;
        for (PolicyHit hit : hits) {
            PolicyChunk chunk = hit.chunk();
            if (!chunk.canExempt() || hit.score() < minScore) {
                continue;
            }
            boolean inScope = chunk.scope().stream().anyMatch(pattern -> matches(pattern, path));
            if (!inScope) {
                continue;
            }
            if (best == null || hit.score() > best.score()) {
                best = new PolicyExemption(chunk.id(), hit.score(), chunk.exemptedRules());
            }
        }
        return best;
    }

    /**
     * A small glob: {@code **} crosses directories, {@code *} stays inside one, and a pattern
     * without a wildcard is a prefix. Scope is written by a human, so being forgiving here is
     * safer than skipping an exemption because of a trailing slash.
     */
    public static boolean matches(String pattern, String path) {
        if (pattern == null || pattern.isBlank() || path == null) {
            return false;
        }
        String cleanPattern = pattern.strip().replace('\\', '/');
        String cleanPath = path.replace('\\', '/');
        if (cleanPattern.indexOf('*') < 0 && cleanPattern.indexOf('?') < 0) {
            String prefix = cleanPattern.endsWith("/") ? cleanPattern : cleanPattern + "/";
            return cleanPath.equals(cleanPattern) || cleanPath.startsWith(prefix);
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < cleanPattern.length(); i++) {
            char c = cleanPattern.charAt(i);
            if (c == '*' && i + 1 < cleanPattern.length() && cleanPattern.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append('.');
            } else if ("\\.[](){}+^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return Pattern.compile(regex.toString()).matcher(cleanPath).matches();
    }
}
