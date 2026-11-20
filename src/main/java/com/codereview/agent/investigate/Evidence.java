package com.codereview.agent.investigate;

/**
 * One citable piece of evidence: whatever a read-only tool brought back, with a stable id.
 *
 * <p>The id is the whole point. A finding may only cite evidence it actually received, and the
 * verifier can therefore check a claim mechanically instead of asking a person to judge whether
 * the model made something up.
 */
public record Evidence(String id, String tool, String file, int startLine, int endLine, String text) {

    public boolean covers(String path, int line) {
        return file.equals(path) && line >= startLine && line <= endLine;
    }

    public boolean mentions(String symbol) {
        return !symbol.isEmpty() && text.contains(symbol);
    }

    public String cite() {
        return id + " " + file + ":" + startLine + "-" + endLine;
    }
}
