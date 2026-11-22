package com.codereview.agent.triage.model;

/**
 * What the author says the change is for, taken from a pull request description or a text file.
 *
 * <p>This is a claim, not evidence. The author can be wrong, and the field is free text supplied by
 * whoever opened the change, so it is never parsed into instructions and never counted as proof of
 * anything. It exists because the intent scan otherwise guesses what a diff is for from file names
 * and method names; the person who wrote the change already said it.
 */
public record AuthorIntent(String title, String body, String source) {

    private static final int MAX_BODY = 2000;

    public AuthorIntent {
        title = title == null ? "" : title.strip();
        body = body == null ? "" : body.strip();
        source = source == null ? "" : source.strip();
        if (body.length() > MAX_BODY) {
            body = body.substring(0, MAX_BODY);
        }
    }

    public static AuthorIntent empty() {
        return new AuthorIntent("", "", "");
    }

    public boolean isEmpty() {
        return title.isEmpty() && body.isEmpty();
    }

    /** Reads a plain text description: the first non-empty line is the title, the rest is the body. */
    public static AuthorIntent fromText(String text, String source) {
        if (text == null || text.isBlank()) {
            return empty();
        }
        String[] lines = text.strip().split("\\R", -1);
        String title = "";
        int start = 0;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                title = lines[i].strip();
                start = i + 1;
                break;
            }
        }
        StringBuilder body = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            body.append(lines[i]).append('\n');
        }
        return new AuthorIntent(title, body.toString(), source);
    }

    /** For the intent prompt: labelled as the author's own words, not as something to trust. */
    public String describe() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("作者对这次改动的说明（作者自己的说法，不是证据）：");
        if (!title.isEmpty()) {
            text.append("标题：").append(title).append("；");
        }
        if (!body.isEmpty()) {
            text.append("描述：").append(body);
        }
        return text.toString();
    }
}
