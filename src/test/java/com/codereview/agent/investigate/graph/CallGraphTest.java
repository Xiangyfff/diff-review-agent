package com.codereview.agent.investigate.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The call graph is worth its cost only if it disagrees with the name index, so the fixture is built
 * to make them disagree: two classes declare a method with the same name and the caller calls one of
 * them.
 */
class CallGraphTest {

    private Path root;

    @BeforeEach
    void createProject() throws IOException {
        root = Files.createTempDirectory(Path.of("target"), "callgraph-");
        write("src/main/java/x/Order.java", """
                package x;

                public class Order {
                }
                """);
        write("src/main/java/x/OrderDao.java", """
                package x;

                public class OrderDao {
                    public void save(Order order) {
                        System.out.println(order);
                    }
                }
                """);
        write("src/main/java/x/AuditDao.java", """
                package x;

                public class AuditDao {
                    public void save(Order order) {
                        System.out.println("audit " + order);
                    }
                }
                """);
        write("src/main/java/x/Caller.java", """
                package x;

                public class Caller {
                    private final OrderDao orders = new OrderDao();
                    private final AuditDao audits = new AuditDao();

                    public void run(Order order) {
                        orders.save(order);
                        audits.save(order);
                        String note = "save";
                        System.out.println(note);
                    }
                }
                """);
    }

    @AfterEach
    void deleteProject() throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void resolvesOnlyTheCallThatTargetsThisDeclaration() {
        CallGraph graph = new CallGraph(root);

        List<CallGraph.Call> callers =
                graph.callersOf("src/main/java/x/OrderDao.java", 4, "save");

        assertThat(callers).hasSize(1);
        assertThat(callers.get(0).file()).isEqualTo("src/main/java/x/Caller.java");
        assertThat(callers.get(0).snippet()).contains("orders.save(order)");
    }

    @Test
    void reportsNothingForAMethodNobodyCalls() {
        CallGraph graph = new CallGraph(root);

        assertThat(graph.callersOf("src/main/java/x/OrderDao.java", 4, "delete")).isEmpty();
    }

    @Test
    void missingFileIsNotAnError() {
        CallGraph graph = new CallGraph(root);

        assertThat(graph.callersOf("src/main/java/x/Missing.java", 1, "save")).isEmpty();
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
