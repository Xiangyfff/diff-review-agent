package com.codereview.agent.triage.model;

/**
 * One thing the triage stage wants the investigation stage to answer.
 *
 * <p>A question must be anchored: {@code target} names a symbol or a location that exists
 * in the unit. That is checkable without a model, and it is what stops the triage stage from
 * handing over a list of vague worries. A question that names nothing cannot be investigated
 * and is rejected before it costs anything.
 */
public record FindingQuestion(String target, String text) {

    public boolean isWellFormed() {
        return target != null && !target.isBlank()
                && text != null && !text.isBlank()
                && text.length() <= 300;
    }

    public String describe() {
        return target + ": " + text;
    }

    /**
     * Pulls the symbol out of a change description, where recognisers quote it in backticks,
     * and falls back to the declaration name. Shared so the mock and the plan assembler
     * agree on what a target is.
     */
    public static String symbolIn(String detail, String fallback) {
        int open = detail == null ? -1 : detail.indexOf('`');
        int close = open < 0 ? -1 : detail.indexOf('`', open + 1);
        if (open >= 0 && close > open) {
            return detail.substring(open + 1, close);
        }
        String symbol = fallback == null ? "" : fallback;
        int paren = symbol.indexOf('(');
        if (paren > 0) {
            symbol = symbol.substring(0, paren);
        }
        int dot = symbol.lastIndexOf('.');
        return dot >= 0 ? symbol.substring(dot + 1) : symbol;
    }
}
