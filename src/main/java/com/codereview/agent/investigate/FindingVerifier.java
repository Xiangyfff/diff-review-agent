package com.codereview.agent.investigate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a finding against the evidence it cites, mechanically.
 *
 * <p>Five things must hold: the statement is a statement, every cited id exists, the line it points
 * at is covered by something it cited or sits inside the unit under review, every symbol it puts in
 * backticks actually appears in the cited text (or is rebuilt only from names that do), and a
 * cited policy clause is quoted in the conclusion word for word. The fourth rule is the one that
 * kills invented code: a model can write any name it likes, but only names it has seen come back
 * from a tool can survive. The fifth is the
 * same idea applied to rules: naming a clause is not evidence, repeating its wording is.
 */
public final class FindingVerifier {

    private static final int MAX_STATEMENT = 300;
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]{1,80})`");
    private static final Pattern POLICY_ID = Pattern.compile("pol-[A-Za-z0-9][A-Za-z0-9-]*");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    /** Chinese characters that count as quoting a clause; eight collide on common phrasing. */
    private static final int QUOTE_WIDTH = 10;

    public Validation verify(Finding finding, EvidenceStore store) {
        return verify(finding, store, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /**
     * The unit under review is trusted input: it comes from the diff, not from the model, so a
     * conclusion may point at its own lines without citing a tool that read them. Everything
     * outside the unit still has to be covered by cited evidence.
     */
    public Validation verify(Finding finding, EvidenceStore store, int unitStart, int unitEnd) {
        return verify(finding, store, unitStart, unitEnd, "");
    }

    /**
     * The diff itself is trusted input, not model output: a conclusion about a removed annotation
     * or a changed comparison must name the text that is no longer there, so symbols are accepted
     * when they appear in the unit's own before/after text as well as in the cited evidence.
     * Invented names still die, because neither source contains them.
     */
    public Validation verify(Finding finding, EvidenceStore store, int unitStart, int unitEnd,
                             String trustedText) {
        return verify(finding, store, unitStart, unitEnd, trustedText, java.util.Map.of());
    }

    /**
     * As above, plus a rule for policy citations: an id starting with {@code pol-} — whether it is
     * listed as evidence or only named in the statement — must be a clause this repository actually
     * has, and the conclusion must carry a stretch of that clause's own wording. Quoting the rule is
     * what makes "this violates policy" different from an opinion; without the quote the model could
     * cite a plausible-sounding id and a rule it invented.
     */
    public Validation verify(Finding finding, EvidenceStore store, int unitStart, int unitEnd,
                             String trustedText, java.util.Map<String, String> clauseTexts) {
        if (finding.statement() == null || finding.statement().isBlank()) {
            return Validation.rejected("empty statement");
        }
        if (!Finding.FIX.equals(finding.demand()) && !Finding.CONFIRM.equals(finding.demand())) {
            return Validation.rejected("demand must be \"fix\" or \"confirm\", not \""
                    + finding.demand() + "\"");
        }
        if (finding.statement().length() > MAX_STATEMENT) {
            return Validation.rejected("statement longer than " + MAX_STATEMENT + " characters");
        }
        if (finding.evidenceIds().isEmpty()) {
            return Validation.rejected("no evidence cited");
        }
        java.util.LinkedHashSet<String> citedClauses = new java.util.LinkedHashSet<>();
        for (String id : finding.evidenceIds()) {
            if (id.startsWith("pol-")) {
                citedClauses.add(id);
            }
        }
        // A conclusion can name a clause in its prose without listing it as evidence ("不符合
        // pol-logging-1 的规范"). That is still a citation, so the wording rule has to cover it;
        // otherwise naming an id in the sentence is a way around the check.
        Matcher mentioned = POLICY_ID.matcher(finding.statement());
        while (mentioned.find()) {
            citedClauses.add(mentioned.group());
        }
        StringBuilder quotedClauses = new StringBuilder();
        for (String id : citedClauses) {
            String clause = clauseTexts.get(id);
            if (clause == null) {
                return Validation.rejected("cited policy clause " + id + " does not exist");
            }
            if (!quotesClause(finding.statement(), clause)) {
                return Validation.rejected("the statement cites " + id
                        + " but does not quote any of its wording");
            }
            quotedClauses.append(clause).append('\n');
        }
        List<Evidence> cited = new ArrayList<>();
        for (String id : finding.evidenceIds()) {
            if (id.startsWith("pol-")) {
                continue;
            }
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
            boolean seen = cited.stream().anyMatch(evidence -> mentionsInText(evidence.text(), symbol))
                    || mentionsInText(trustedText, symbol)
                    || mentionsInText(quotedClauses.toString(), symbol);
            if (!seen) {
                return Validation.rejected("`" + symbol + "` does not appear in the cited evidence");
            }
        }
        return Validation.accepted();
    }

    private static boolean mentionsInText(String text, String symbol) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.contains(symbol) || text.contains(bareName(symbol))
                || namesAppearInOrder(symbol, text);
    }

    /**
     * A suggestion is often the evidence's own line rebuilt from memory: "log.info(\"audited order
     * {}\", order.getName())" for a line the evidence spells with one more argument than the model
     * kept. It invents no name, only punctuation, and rejecting it is a false negative of our own
     * gate — the model saw everything it wrote. So a multi-name snippet also counts as seen when
     * its identifiers appear in the text in the same order. A name the model made up still fails,
     * because that identifier is nowhere in the text.
     */
    private static boolean namesAppearInOrder(String symbol, String text) {
        List<String> wanted = identifiers(symbol);
        if (wanted.size() < 2) {
            return false;
        }
        int at = 0;
        for (String token : identifiers(text)) {
            if (token.equals(wanted.get(at))) {
                at++;
                if (at == wanted.size()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> identifiers(String text) {
        List<String> names = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group());
        }
        return names;
    }

    /**
     * True when the statement repeats a stretch of the clause verbatim. For a Chinese clause ten
     * consecutive Chinese characters count, not eight: "使用日志框架，不" is common phrasing and
     * turned up in a conclusion that never quoted the rule ("没有使用日志框架，不符合…"), while ten
     * is long enough that only real copying matches. Only the Chinese is compared, so a clause that spells
     * "System.out" is not counted as quoted by any statement about the same code; punctuation and
     * whitespace are dropped on both sides first. A clause without that much Chinese falls back to
     * eight characters of its literal text.
     */
    static boolean quotesClause(String statement, String clause) {
        String flatStatement = statement.replaceAll("\\s+", "");
        String flatClause = clause.replaceAll("\\s+", "");
        String clauseHan = hanOnly(flatClause);
        if (clauseHan.length() >= QUOTE_WIDTH) {
            return shares(clauseHan, hanOnly(flatStatement), QUOTE_WIDTH);
        }
        return shares(flatClause, flatStatement, 8);
    }

    private static boolean shares(String wording, String statement, int width) {
        if (wording.length() < width) {
            return false;
        }
        for (int start = 0; start + width <= wording.length(); start++) {
            if (statement.contains(wording.substring(start, start + width))) {
                return true;
            }
        }
        return false;
    }

    private static String hanOnly(String text) {
        StringBuilder kept = new StringBuilder(text.length());
        text.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .forEach(kept::appendCodePoint);
        return kept.toString();
    }

    private static String bareName(String symbol) {
        return symbol.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
    }

    /**
     * A model writes symbols the way it reads them, which often includes the parameter list
     * ("save(Order)") while the evidence holds the declaration ("public void save(Order order) {").
     * Rejecting that is a false negative of our own gate, so the bare name is accepted too.
     */
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
