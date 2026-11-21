package com.codereview.agent.triage.staticanalysis;

/**
 * One thing a static check noticed on a line that this change added.
 *
 * <p>Only warnings that land on added lines survive: an old defect somewhere else in the file is
 * not this author's job, and reporting it is how a review tool teaches people to ignore it.
 */
public record StaticWarning(String kind, int line, String message) {

    public String describe() {
        return "第 " + line + " 行 " + kind + "：" + message;
    }
}
