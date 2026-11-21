package com.codereview.agent.triage.screen;

/**
 * A file the review skipped, and why. Kept in the plan so a reader can tell "nothing changed
 * there" from "we decided not to look", and so a path can be pulled back in on the next run.
 */
public record ScreenedFile(String path, String reason) {
}
