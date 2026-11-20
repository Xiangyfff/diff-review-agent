package com.codereview.agent.verify;

/**
 * @param maxToolCalls how many read-only calls the critic may make per finding; deliberately
 *                     smaller than the investigator's budget, because refuting is supposed to be
 *                     cheaper than proving
 */
public record VerifyConfig(int maxToolCalls) {

    public VerifyConfig {
        if (maxToolCalls < 1) {
            throw new IllegalArgumentException("the critic needs at least one tool call");
        }
    }

    public static VerifyConfig defaults() {
        return new VerifyConfig(4);
    }
}
