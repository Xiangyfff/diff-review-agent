package com.codereview.agent.triage.structure;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds literal tokens in a rendered statement or body, and blanks them out.
 *
 * <p>Two statements that differ only in a number or a string are one shape and two intentions.
 * The statement diff pairs them, this class answers "do they differ only there", and the caller
 * decides what that means.
 */
final class Literals {

    private static final Pattern TOKEN = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"
                    + "|\\bnull\\b|\\btrue\\b|\\bfalse\\b"
                    + "|(?<![\\w.])(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)"
                    + "(?![\\w.])");

    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\s*(?:this\\.)?(?:(?:[A-Za-z_$][\\w$]*\\.)*(?:log|logger|LOG|LOGGER)|System\\.(?:out|err))"
                    + "\\.(?:trace|debug|info|warn|error|print|printf|println)\\s*\\(");

    private Literals() {
    }

    /** True when a line is a logging call; rewriting its message is not a behaviour change. */
    static boolean isLogging(String line) {
        return LOG_LINE.matcher(line).find();
    }

    /**
     * True when every differing line is a logging line. A reworded log message is an edit the
     * author meant to make, and nothing downstream can act on it, so it must not gate work.
     */
    static boolean onlyLoggingLinesDiffer(String before, String after) {
        List<String> oldLines = before == null ? List.of() : before.lines().toList();
        List<String> newLines = after == null ? List.of() : after.lines().toList();
        if (oldLines.size() != newLines.size()) {
            return false;
        }
        boolean changed = false;
        for (int index = 0; index < oldLines.size(); index++) {
            String oldLine = oldLines.get(index);
            String newLine = newLines.get(index);
            if (oldLine.equals(newLine)) {
                continue;
            }
            if (!isLogging(oldLine) || !isLogging(newLine)) {
                return false;
            }
            changed = true;
        }
        return changed;
    }

    static String blank(String text) {
        return TOKEN.matcher(text).replaceAll("\u0002");
    }

    static Set<String> values(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /** True when the two texts differ, but every difference sits inside a literal token. */
    static boolean onlyLiteralsDiffer(String before, String after) {
        return !before.equals(after) && blank(before).equals(blank(after));
    }
}
