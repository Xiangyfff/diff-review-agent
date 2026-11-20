package com.codereview.agent.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The evaluation corpus: a small hand-written project plus a sequence of changes applied on top of
 * it one commit at a time. Some changes introduce a defect whose exact line is known, some are
 * clean refactors that must not be reported. Because the defects are ours, the expected answer
 * needs no human labelling: that is the whole point of building the corpus this way.
 *
 * <p>Every search string must occur exactly once, so a refactor of the corpus fails loudly instead
 * of silently degrading the measurement.
 */
public final class EvalCorpus {

    private EvalCorpus() {
    }

    public static Map<String, String> baseFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/com/example/order/OrderService.java", orderService());
        files.put("src/main/java/com/example/order/PriceCalculator.java", priceCalculator());
        files.put("src/main/java/com/example/order/OrderRepository.java", orderRepository());
        files.put("src/main/java/com/example/order/OrderController.java", orderController());
        files.put("src/main/java/com/example/auth/AuthService.java", authService());
        return files;
    }

    public static List<MutationCase> cases() {
        String service = "src/main/java/com/example/order/OrderService.java";
        String price = "src/main/java/com/example/order/PriceCalculator.java";
        String repository = "src/main/java/com/example/order/OrderRepository.java";
        String controller = "src/main/java/com/example/order/OrderController.java";
        String auth = "src/main/java/com/example/auth/AuthService.java";

        return List.of(
                new MutationCase("null-guard-removed", service,
                        join("        if (order == null) {", "            return null;", "        }"),
                        "", "return repository.findByName(order.getName());", true,
                        "null-guard"),

                new MutationCase("exception-swallowed", service,
                        join("        repository.save(order);", "        audit(order);", "    }"),
                        join("        try {", "            repository.save(order);",
                                "            audit(order);", "        } catch (RuntimeException e) {",
                                "        }", "    }"),
                        "catch (RuntimeException e)", true, "exception"),

                new MutationCase("early-exit-removed", service,
                        join("        if (order.getLines().isEmpty()) {",
                                "            throw new IllegalArgumentException(\"order has no lines\");",
                                "        }"),
                        "", "return base + order.getLines().size();", true, "early-exit"),

                new MutationCase("annotation-removed", service,
                        join("    @Transactional", "    public void save(Order order) {"),
                        "    public void save(Order order) {",
                        "public void save(Order order)", true, "contract"),

                new MutationCase("log-line-added", service,
                        "        int base = zone * 3;",
                        join("        log.debug(\"computing shipping for zone {}\", zone);",
                                "        int base = zone * 3;"),
                        "log.debug(", false, "clean"),

                new MutationCase("condition-inverted", price,
                        "        if (bulk) {", "        if (!bulk) {", "!bulk", true, "condition"),

                new MutationCase("synchronized-added", price,
                        "    public int applyTax(int amount) {",
                        "    public synchronized int applyTax(int amount) {",
                        "public synchronized int applyTax", true, "concurrency"),

                new MutationCase("local-renamed", price,
                        join("        int total = unitPrice * quantity;", "        return total;"),
                        join("        int amount = unitPrice * quantity;", "        return amount;"),
                        "int amount = unitPrice * quantity;", false, "clean"),

                new MutationCase("constant-extracted", price,
                        "        return amount + amount / 10;",
                        join("        int tax = amount / 10;", "        return amount + tax;"),
                        "int tax = amount / 10;", false, "clean"),

                new MutationCase("resource-unclosed", repository,
                        "        return Files.readAllLines(file);",
                        join("        FileInputStream in = new FileInputStream(file.toFile());",
                                "        return Files.readAllLines(file);"),
                        "new FileInputStream(file.toFile())", true, "resource"),

                new MutationCase("query-concatenated", repository,
                        "        return \"select * from orders where name = ?\";",
                        "        return \"select * from orders where name = '\" + name + \"'\";",
                        "where name = '\" + name", true, "injection"),

                new MutationCase("comment-added", repository,
                        "    public void save(Order order) {",
                        join("    // Stub until the persistence layer lands.",
                                "    public void save(Order order) {"),
                        "// Stub until the persistence layer lands.", false, "clean"),

                new MutationCase("visibility-narrowed", controller,
                        "    public String handle(String id) {",
                        "    private String handle(String id) {",
                        "private String handle", true, "contract"),

                new MutationCase("signature-changed", controller,
                        "    public String submit(Order order) {",
                        "    public String submit(Order order, int zone) {",
                        "public String submit(Order order, int zone)", true, "contract"),

                new MutationCase("statements-reordered", controller,
                        join("        names.add(\"first\");", "        names.add(\"second\");"),
                        join("        names.add(\"second\");", "        names.add(\"first\");"),
                        "names.add(\"second\");", false, "clean"),

                new MutationCase("auth-null-checks-removed", auth,
                        "        return user != null && password != null && user.equals(password);",
                        "        return user.equals(password);",
                        "return user.equals(password);", true, "security"));
    }

    private static String join(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static String orderService() {
        return join(
                "package com.example.order;",
                "",
                "import org.slf4j.Logger;",
                "import org.slf4j.LoggerFactory;",
                "import org.springframework.transaction.annotation.Transactional;",
                "",
                "public class OrderService {",
                "",
                "    private static final Logger log = LoggerFactory.getLogger(OrderService.class);",
                "",
                "    private final OrderRepository repository;",
                "",
                "    public OrderService(OrderRepository repository) {",
                "        this.repository = repository;",
                "    }",
                "",
                "    public Order find(Order order) {",
                "        if (order == null) {",
                "            return null;",
                "        }",
                "        return repository.findByName(order.getName());",
                "    }",
                "",
                "    public void persist(Order order) {",
                "        repository.save(order);",
                "        audit(order);",
                "    }",
                "",
                "    @Transactional",
                "    public void save(Order order) {",
                "        validate(order);",
                "        repository.save(order);",
                "    }",
                "",
                "    public void validate(Order order) {",
                "        if (order == null) {",
                "            throw new IllegalArgumentException(\"order must not be null\");",
                "        }",
                "        if (order.getLines().isEmpty()) {",
                "            throw new IllegalArgumentException(\"order has no lines\");",
                "        }",
                "    }",
                "",
                "    public int computeShipping(Order order, int zone) {",
                "        int base = zone * 3;",
                "        return base + order.getLines().size();",
                "    }",
                "",
                "    private void audit(Order order) {",
                "        log.info(\"audited order {}\", order.getName());",
                "    }",
                "}");
    }

    private static String priceCalculator() {
        return join(
                "package com.example.order;",
                "",
                "public class PriceCalculator {",
                "",
                "    public int discount(int quantity) {",
                "        boolean bulk = quantity > 10;",
                "        if (bulk) {",
                "            return quantity * 9;",
                "        }",
                "        return quantity * 10;",
                "    }",
                "",
                "    public int applyTax(int amount) {",
                "        return amount + amount / 10;",
                "    }",
                "",
                "    public int subtotal(int unitPrice, int quantity) {",
                "        int total = unitPrice * quantity;",
                "        return total;",
                "    }",
                "}");
    }

    private static String orderRepository() {
        return join(
                "package com.example.order;",
                "",
                "import java.io.FileInputStream;",
                "import java.io.IOException;",
                "import java.nio.file.Files;",
                "import java.nio.file.Path;",
                "import java.util.List;",
                "import java.util.Objects;",
                "",
                "public class OrderRepository {",
                "",
                "    private final Path root;",
                "",
                "    public OrderRepository(Path root) {",
                "        this.root = Objects.requireNonNull(root);",
                "    }",
                "",
                "    public List<String> readAll(Path file) throws IOException {",
                "        return Files.readAllLines(file);",
                "    }",
                "",
                "    public String findByName(String name) {",
                "        return \"select * from orders where name = ?\";",
                "    }",
                "",
                "    public void save(Order order) {",
                "        // no-op stub",
                "    }",
                "}");
    }

    private static String orderController() {
        return join(
                "package com.example.order;",
                "",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "",
                "public class OrderController {",
                "",
                "    private final OrderService service;",
                "",
                "    public OrderController(OrderService service) {",
                "        this.service = service;",
                "    }",
                "",
                "    public String handle(String id) {",
                "        Order order = service.find(new Order(id));",
                "        if (order == null) {",
                "            return \"not found\";",
                "        }",
                "        return order.getName();",
                "    }",
                "",
                "    public String submit(Order order) {",
                "        service.save(order);",
                "        return \"ok\";",
                "    }",
                "",
                "    public List<String> list() {",
                "        List<String> names = new ArrayList<>();",
                "        names.add(\"first\");",
                "        names.add(\"second\");",
                "        return names;",
                "    }",
                "}");
    }

    private static String authService() {
        return join(
                "package com.example.auth;",
                "",
                "public class AuthService {",
                "",
                "    public boolean login(String user, String password) {",
                "        return check(user, password);",
                "    }",
                "",
                "    private boolean check(String user, String password) {",
                "        return user != null && password != null && user.equals(password);",
                "    }",
                "}");
    }
}
