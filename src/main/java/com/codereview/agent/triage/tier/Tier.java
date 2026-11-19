package com.codereview.agent.triage.tier;

/** How much the triage stage is willing to spend on a unit. */
public enum Tier {

    /** Investigate regardless of what the model thinks: a rule found certain risk. */
    MUST_INVESTIGATE,

    /** The model decides, and its reason for declining is recorded. */
    JUDGE,

    /** Do not spend a model call on it. */
    SKIP
}
