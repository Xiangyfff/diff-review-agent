package com.codereview.agent.review;

/**
 * How far down the pipeline a run goes. The three values exist to be measured against each other:
 * a number for each stage is the only way to say whether that stage earns its cost.
 */
public enum Depth {

    /** Triage only: every scheduled question becomes a comment, nothing is verified. */
    TRIAGE_ONLY,

    /** Triage plus investigation: findings stand on their evidence, nothing contradicts them. */
    WITH_INVESTIGATION,

    /** The real pipeline: investigated findings are also challenged by the critic. */
    FULL;

    /**
     * Parses the wire name of a depth, defaulting to a full review. The demo sends a name rather
     * than an ordinal so the value stays readable in a request log.
     */
    public static Depth parse(String name) {
        if (name == null || name.isBlank()) {
            return FULL;
        }
        for (Depth depth : values()) {
            if (depth.name().equalsIgnoreCase(name.strip())) {
                return depth;
            }
        }
        throw new IllegalArgumentException("未知的审查深度：" + name);
    }
}
