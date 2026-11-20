package com.codereview.agent.investigate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a finding against the evidence it cites, mechanically.
 *
 * <p>Four things must hold: the statement is a statement, every cited id exists, the line it points
 * at is covered by something it cited or sits inside the unit under review, and every symbol it
 * puts in backticks actually appears in the cited text. The last rule is the one that kills invented code: a model can write any name it
 * likes, but only names it has seen come back from a tool can survive.
 */
public final class FindingVerifier {

    private static final int MAX_STATEMENT = 300;
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]{1,80})`");

    public Validation verify(Finding finding, EvidenceStore store) {
        return verify(finding, store, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /**
     * The unit under review is trusted input: it comes from the diff, not from the model, so a
     * conclusion may point at its own lines without citing a tool that read them. Everything
     * outside the unit still has to be covered by cited evidence.
     */
    public Validation verify(Finding finding, EvidenceStore store, int unitStart, int unitEnd) {
        if (finding.statement() == null || finding.statement().isBlank()) {
            return Validation.rejected("empty statement");
        }
        if (finding.statement().length() > MAX_STATEMENT) {
            return Validation.rejected("statement longer than " + MAX_STATEMENT + " characters");
        }
        if (finding.evidenceIds().isEmpty()) {
            return Validation.rejected("no evidence cited");
        }
        List<Evidence> cited = new ArrayList<>();
        for (String id : finding.evidenceIds()) {
            Optional<Evidence> evidence = store.find(id);
            if (evidence.isEmpty()) {
                return Validation.rejected("cited evidence " + id + " does not exist");
            }
            cited.add(evidence.get());
        }
        boolean insideUnit = finding.line() >= unitStart && finding.line() <= unitEnd;
        boolean covered = insideUnit
                || cited.stream().anyMatch(evidence -> evidence.covers(finding.file(), finding.line()));
        if (!covered) {
            return Validation.rejected("line " + finding.line() + " is not covered by any cited evidence");
        }
        for (String symbol : backticked(finding.statement())) {
            boolean seen = cited.stream().anyMatch(evidence -> evidence.mentions(symbol));
            if (!seen) {
                return Validation.rejected("`" + symbol + "` does not appear in the cited evidence");
            }
        }
        return Validation.accepted();
    }

    static List<String> backticked(String statement) {
        List<String> symbols = new ArrayList<>();
        Matcher matcher = BACKTICKED.matcher(statement);
        while (matcher.find()) {
            symbols.add(matcher.group(1));
        }
        return symbols;
    }

    public record Validation(boolean ok, String reason) {

        static Validation accepted() {
            return new Validation(true, "ok");
        }

        static Validation rejected(String reason) {
            return new Validation(false, reason);
        }
    }
}
