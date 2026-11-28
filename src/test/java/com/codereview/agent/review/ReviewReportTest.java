package com.codereview.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.codereview.agent.triage.plan.TriageStats;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewReportTest {

    private static final TriageStats EMPTY = new TriageStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    @Test
    void changeRequestsComeBeforeOpenQuestions() {
        Comment question = new Comment("A.java", 40, "LOGIC", Comment.Status.QUESTION, "确认？",
                List.of());
        Comment assertion = new Comment("B.java", 10, "SECURITY", Comment.Status.ASSERTION, "要改。",
                List.of("ev-1"));

        ReviewReport report = new ReviewReport("base", "head", EMPTY, List.of(), List.of(question,
                assertion), 1, 0, 0, 0, List.of(), 1, 0);

        assertThat(report.comments()).extracting(Comment::status)
                .containsExactly(Comment.Status.ASSERTION, Comment.Status.QUESTION);
    }

    @Test
    void repeatedUnverifiedQuestionsInOneFileCollapseIntoOneComment() {
        String body = "这个方法被删除了：如果仓库里还有调用方，删除会让它们编译失败或行为改变；深挖没有取到能下结论的证据，请确认删除是有意的。";
        List<Comment> questions = List.of(
                new Comment("A.java", 10, "CONTRACT", Comment.Status.QUESTION, body, List.of()),
                new Comment("A.java", 20, "CONTRACT", Comment.Status.QUESTION, body, List.of()),
                new Comment("A.java", 30, "CONTRACT", Comment.Status.QUESTION, body, List.of()));

        ReviewReport report = new ReviewReport("base", "head", EMPTY, List.of(), questions,
                3, 0, 0, 0, List.of(), 3, 0);

        assertThat(report.comments()).hasSize(1);
        assertThat(report.comments().get(0).body()).contains("共 3 处").contains("第 10、20、30 行");
        assertThat(report.merged()).isEqualTo(2);
    }

    @Test
    void questionsWithEvidenceAreNotCollapsed() {
        Comment first = new Comment("A.java", 10, "LOGIC", Comment.Status.QUESTION, "确认？",
                List.of("ev-1"));
        Comment second = new Comment("A.java", 20, "LOGIC", Comment.Status.QUESTION, "确认？",
                List.of("ev-2"));

        ReviewReport report = new ReviewReport("base", "head", EMPTY, List.of(),
                List.of(first, second), 2, 0, 0, 0, List.of(), 2, 0);

        assertThat(report.comments()).hasSize(2);
    }
}
