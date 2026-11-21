package com.codereview.agent.review;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the wiring between the three stages, not the stages themselves: a change goes in, a
 * comment with evidence comes out, and every outcome stays countable.
 */
class ReviewPipelineTest {

    private Path repository;

    @BeforeEach
    void createRepository() throws Exception {
        repository = Path.of("target", "test-review", Long.toHexString(System.nanoTime()));
        Files.createDirectories(repository);
    }

    @AfterEach
    void deleteRepository() throws Exception {
        try (var walk = Files.walk(repository)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                path.toFile().setWritable(true);
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best effort: a leftover directory must not fail the test
                }
            });
        }
    }

    @Test
    void aRemovedNullCheckProducesACommentWithEvidence() throws Exception {
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            write("src/main/java/shop/OrderService.java", """
                    package shop;

                    public class OrderService {

                        private final OrderRepository repository;

                        public OrderService(OrderRepository repository) {
                            this.repository = repository;
                        }

                        public Order find(Order order) {
                            if (order == null) {
                                return null;
                            }
                            return repository.findByName(order.getName());
                        }
                    }
                    """);
            commit(git, "base");
            String base = git.getRepository().resolve("HEAD").name();

            write("src/main/java/shop/OrderService.java", """
                    package shop;

                    public class OrderService {

                        private final OrderRepository repository;

                        public OrderService(OrderRepository repository) {
                            this.repository = repository;
                        }

                        public Order find(Order order) {
                            return repository.findByName(order.getName());
                        }
                    }
                    """);
            commit(git, "remove the null check");
            String head = git.getRepository().resolve("HEAD").name();

            ReviewReport report = ReviewPipeline.offline().run(repository, base, head);

            assertThat(report.units()).isNotEmpty();
            assertThat(report.comments()).isNotEmpty();
            Comment comment = report.comments().get(0);
            assertThat(comment.evidenceIds()).isNotEmpty();
            assertThat(comment.riskCategory()).isEqualTo("CONSISTENCY");
            assertThat(comment.status()).isIn(Comment.Status.ASSERTION, Comment.Status.QUESTION);
            assertThat(report.renderMarkdown()).contains("## Comments");
            assertThat(report.renderJson()).contains("\"commentCount\"");
        }
    }

    @Test
    void aCleanRenameStillEndsInARecordableReport() throws Exception {
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            write("src/main/java/shop/PriceCalculator.java", """
                    package shop;

                    public class PriceCalculator {

                        public int withTax(int base) {
                            int rate = 20;
                            return base + base * rate / 100;
                        }
                    }
                    """);
            commit(git, "base");
            String base = git.getRepository().resolve("HEAD").name();

            write("src/main/java/shop/PriceCalculator.java", """
                    package shop;

                    public class PriceCalculator {

                        public int withTax(int base) {
                            int percent = 20;
                            return base + base * percent / 100;
                        }
                    }
                    """);
            commit(git, "rename the local");
            String head = git.getRepository().resolve("HEAD").name();

            ReviewReport report = ReviewPipeline.offline().run(repository, base, head);

            assertThat(report.findings()).isGreaterThanOrEqualTo(0);
            assertThat(report.findings()).isEqualTo(
                    report.comments().size() + report.dropped() + report.merged());
            assertThat(report.renderMarkdown()).contains("Units investigated");
        }
    }

    private void write(String path, String content) throws Exception {
        Path target = repository.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private void commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).call();
    }
}
