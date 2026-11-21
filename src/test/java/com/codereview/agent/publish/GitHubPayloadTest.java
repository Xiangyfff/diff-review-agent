package com.codereview.agent.publish;

import com.codereview.agent.review.Comment;
import com.codereview.agent.review.ReviewReport;
import com.codereview.agent.triage.plan.TriageStats;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ReviewReport report(List<Comment> comments) {
        TriageStats stats = new TriageStats(1, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0);
        return new ReviewReport("base1", "head1", stats, List.of(), comments,
                comments.size(), 0, 0, 0, List.of(), comments.size(), 0);
    }

    private static Comment comment(String file, int line, Comment.Status status) {
        return new Comment(file, line, "LOGIC", status, "restore the null check",
                List.of("ev-2"));
    }

    @Test
    void inlineCommentsCarryPathLineAndTheEvidenceTheyCite() {
        ObjectNode payload = GitHubPayload.review(report(List.of(
                comment("src/A.java", 12, Comment.Status.ASSERTION))),
                List.of(comment("src/A.java", 12, Comment.Status.ASSERTION)));

        JsonNode inline = payload.get("comments").get(0);
        assertThat(inline.get("path").asText()).isEqualTo("src/A.java");
        assertThat(inline.get("line").asInt()).isEqualTo(12);
        assertThat(inline.get("side").asText()).isEqualTo("RIGHT");
        assertThat(inline.get("body").asText()).contains("restore the null check").contains("ev-2");
    }

    @Test
    void theBodySaysHowManyCommentsCouldNotBePlaced() {
        ObjectNode payload = GitHubPayload.review(report(List.of(
                comment("src/A.java", 12, Comment.Status.ASSERTION),
                comment("src/B.java", 99, Comment.Status.QUESTION))),
                List.of(comment("src/A.java", 12, Comment.Status.ASSERTION)));

        String body = payload.get("body").asText();
        assertThat(body).contains("1 of them below because their lines are outside this diff");
        assertThat(payload.get("comments")).hasSize(1);
        assertThat(payload.get("event").asText()).isEqualTo("COMMENT");
    }

    @Test
    void onlyLinesTheDiffTouchedBecomeInlineComments() {
        List<Comment> comments = List.of(
                comment("src/A.java", 12, Comment.Status.ASSERTION),
                comment("src/A.java", 40, Comment.Status.QUESTION));

        List<Comment> inside = DiffLines.inside(comments, Set.of("src/A.java:12"));

        assertThat(inside).hasSize(1);
        assertThat(inside.get(0).line()).isEqualTo(12);
    }

    @Test
    void aCommentWithoutEvidenceIsStillPostable() {
        Comment bare = new Comment("src/A.java", 7, "LOGIC", Comment.Status.QUESTION, "is this reachable?", List.of());
        assertThat(GitHubPayload.body(bare)).isEqualTo("is this reachable?");
    }
}
