package com.codereview.agent.investigate;

import java.util.List;

/**
 * What a tool brought back, before it is given an id.
 *
 * <p>Tools never invent ids and never write into the store; the loop does that, which keeps the
 * numbering honest even when a tool returns several excerpts.
 *
 * @param ok        whether the call was understood and produced something usable
 * @param summary   one line for the model, e.g. "3 lines read from a/Service.java"
 * @param excerpts  the text that may be cited later
 */
public record ToolOutcome(boolean ok, String summary, List<Excerpt> excerpts) {

    public static ToolOutcome failed(String summary) {
        return new ToolOutcome(false, summary, List.of());
    }

    public static ToolOutcome of(String summary, List<Excerpt> excerpts) {
        return new ToolOutcome(true, summary, List.copyOf(excerpts));
    }

    public record Excerpt(String file, int startLine, int endLine, String text) {
    }
}
