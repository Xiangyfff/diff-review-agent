package com.codereview.agent.triage.policy;

/**
 * One retrievable piece of a policy document: a heading and the text under it.
 *
 * <p>The id is what a conclusion may cite, so it is short, stable and never reused. It lives in
 * the {@code pol-} namespace on purpose: a policy excerpt is not code evidence, and the two must
 * never be confused by the verifier.
 *
 * @param mandatory the heading was marked as mandatory, which is the document author saying
 *                  "a change touching this area has to be checked", not the model's opinion
 */
public record PolicyChunk(String id, String file, String heading, String text, boolean mandatory) {

    /** How a citation reads in a prompt or a comment. */
    public String cite() {
        return id + " " + file + " / " + heading;
    }

    /** A bounded excerpt for prompts, so one long section cannot swallow the budget. */
    public String excerpt(int width) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= width ? flat : flat.substring(0, width) + "...";
    }
}
