package com.codereview.agent.publish;

import com.codereview.agent.review.Comment;
import com.codereview.agent.review.ReviewReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The JSON a pull request review is created from.
 *
 * <p>Built here, apart from the HTTP call, so the shape can be asserted without a network. The
 * body is always posted: GitHub rejects an inline comment whose line is not in the pull request's
 * diff, and a comment that lands nowhere is worse than one that lands in the summary.
 */
public final class GitHubPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GitHubPayload() {
    }

    public static ObjectNode review(ReviewReport report, List<Comment> inline) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("body", markdown(report, inline.size()));
        root.put("event", "COMMENT");
        ArrayNode comments = root.putArray("comments");
        for (Comment comment : inline) {
            ObjectNode node = comments.addObject();
            node.put("path", comment.file());
            node.put("line", comment.line());
            node.put("side", "RIGHT");
            node.put("body", body(comment));
        }
        return root;
    }

    /** One comment as it appears inline: the claim, then the evidence it is allowed to cite. */
    public static String body(Comment comment) {
        StringBuilder text = new StringBuilder(comment.body());
        if (!comment.evidenceIds().isEmpty()) {
            text.append("\n\n<sub>evidence: ");
            text.append(String.join(", ", comment.evidenceIds()));
            text.append("</sub>");
        }
        return text.toString();
    }

    static String markdown(ReviewReport report, int inlineCount) {
        StringBuilder text = new StringBuilder();
        text.append("**diff-review-agent** reviewed ").append(report.baseRevision())
                .append("..").append(report.headRevision()).append(". ")
                .append(report.assertions()).append(" assertion(s), ")
                .append(report.questions()).append(" question(s)");
        if (inlineCount < report.comments().size()) {
            text.append(", ").append(report.comments().size() - inlineCount)
                    .append(" of them below because their lines are outside this diff");
        }
        text.append(".**\n\n");
        for (Comment comment : report.comments()) {
            text.append("- `").append(comment.file()).append(":").append(comment.line()).append("` ");
            text.append(comment.status() == Comment.Status.ASSERTION ? "assertion" : "question");
            text.append(" — ").append(comment.body().replace('\n', ' ')).append('\n');
        }
        text.append("\n<sub>Every claim above cites evidence gathered from this repository. ")
                .append("Comments that could not be backed by evidence were dropped before posting.</sub>");
        return text.toString();
    }
}
