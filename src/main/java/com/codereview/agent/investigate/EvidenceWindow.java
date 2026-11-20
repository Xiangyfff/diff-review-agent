package com.codereview.agent.investigate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides how much of the evidence store goes into the prompt.
 *
 * <p>The store keeps everything, and nothing is ever deleted: the conclusion-time checks read the
 * store, not the prompt. What shrinks is the window. The newest few entries are kept verbatim — they
 * are what the model is working on — and older ones collapse to one line that still carries the id and
 * the location, so they stay citable and re-readable through a tool call.
 *
 * <p>Anything pinned is kept verbatim regardless of age. The verifier pins the evidence the finding
 * cites: a critic that cannot read the evidence it is judging is not refuting, it is guessing.
 */
public final class EvidenceWindow {

    /** The newest entries kept verbatim when nothing is pinned. */
    public static final int DEFAULT_FULL_LIMIT = 4;

    private static final int DIGEST_WIDTH = 100;

    /** How much of one excerpt is shown. A forty-line read is about 1,500 characters. */
    private static final int FULL_WIDTH = 1_800;

    private EvidenceWindow() {
    }

    public record Line(Evidence evidence, boolean folded, String text) {
    }

    public static List<Line> shape(List<Evidence> evidence, Set<String> pinned, int fullLimit) {
        if (evidence.size() <= fullLimit) {
            return evidence.stream().map(item -> new Line(item, false, full(item))).toList();
        }
        int firstFull = evidence.size() - fullLimit;
        List<Line> lines = new ArrayList<>(evidence.size());
        for (int index = 0; index < evidence.size(); index++) {
            Evidence item = evidence.get(index);
            boolean keep = pinned.contains(item.id()) || index >= firstFull;
            lines.add(new Line(item, !keep, keep ? full(item) : digest(item)));
        }
        return List.copyOf(lines);
    }

    public static String render(List<Evidence> evidence, Set<String> pinned, int fullLimit) {
        StringBuilder text = new StringBuilder();
        for (Line line : shape(evidence, pinned, fullLimit)) {
            text.append("- ").append(line.evidence().cite());
            if (line.folded()) {
                text.append("（已折叠，原文仍在证据库，可再次读取）：").append(line.text());
            } else {
                text.append("：").append(line.text());
            }
            text.append('\n');
        }
        return text.toString();
    }

    public static long foldedCount(List<Evidence> evidence, Set<String> pinned, int fullLimit) {
        return shape(evidence, pinned, fullLimit).stream().filter(Line::folded).count();
    }

    public static Set<String> ids(List<Evidence> evidence) {
        Set<String> ids = new LinkedHashSet<>();
        evidence.forEach(item -> ids.add(item.id()));
        return ids;
    }

    /**
     * The excerpt itself, not just its first line. A model that can only see the opening line of what
     * it read will read the same file again, and again, until the budget is gone — which is exactly
     * what happened the first time this was measured against a real model.
     */
    private static String full(Evidence evidence) {
        String text = evidence.text() == null ? "" : evidence.text().strip();
        if (text.isEmpty()) {
            return "（空）";
        }
        return text.length() > FULL_WIDTH ? text.substring(0, FULL_WIDTH) + "\n…（已截断）" : text;
    }

    private static String digest(Evidence evidence) {
        return firstLine(evidence.text(), DIGEST_WIDTH);
    }

    private static String firstLine(String text, int limit) {
        if (text == null || text.isBlank()) {
            return "（空）";
        }
        String first = text.strip().lines().findFirst().orElse("");
        return first.length() > limit ? first.substring(0, limit) + "…" : first;
    }
}
