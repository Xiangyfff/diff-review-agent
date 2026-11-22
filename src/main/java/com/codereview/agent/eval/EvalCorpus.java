package com.codereview.agent.eval;

import java.util.LinkedHashMap;
import java.util.ArrayList;
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
        files.put("docs/policies/security.md", securityPolicy());
        files.put("docs/policies/logging.md", loggingPolicy());
        return files;
    }

    /**
     * The policy documents the corpus ships with. They are part of the base commit so that policy
     * retrieval is measured on the same footing as every other signal: the documents exist before
     * any change, and a change can violate one.
     */
    public static String securityPolicy() {
        return """
                # 安全与凭据

                ## [强制] 认证失败的日志

                认证失败时只记录用户名与失败原因。不得记录口令、口令散列、令牌或会话标识，
                也不得把它们拼进异常消息。排查需要的字段是时间、用户名、来源地址与失败原因。

                ```java
                // 反例：把口令写进了日志
                log.warn("auth failed for {} pwd={}", account, secret);
                ```

                ```java
                // 正例：只说谁失败了、为什么
                log.warn("auth failed for {} from {}", account, remoteAddress);
                ```

                ## [强制] 口令比较

                口令必须使用恒定时间的比较方式，禁止使用 equals 或 equalsIgnoreCase，
                因为逐字符比较会在第一个不同的字符处返回，泄漏可测量的时间差。

                ```java
                // 反例：逐字符比较，提前返回
                if (storedPassword.equals(submitted)) {
                    return true;
                }
                ```

                ```java
                // 正例：恒定时间比较
                return MessageDigest.isEqual(storedDigest, digest(submitted));
                ```

                ## 会话有效期

                访问令牌的有效期不得超过两小时。刷新令牌应绑定设备指纹并可单独吊销。
                """;
    }

    public static String loggingPolicy() {
        return """
                # 日志与输出

                ## [强制] 生产代码不得直接写标准输出

                生产代码使用日志框架，不允许 System.out 或 System.err。标准输出在容器里
                没有级别、没有结构化字段，也无法被集中采集。

                ```java
                // 反例
                System.out.println("reconciled " + rows + " rows");
                ```

                ```java
                // 正例
                log.info("reconciled {} rows", rows);
                ```

                ## 日志字段

                每条日志至少包含 trace id、用户标识与操作对象。金额、数量等业务数值按
                最小必要原则记录，不得整对象序列化。
                """;
    }

    public static List<MutationCase> cases() {
        String service = "src/main/java/com/example/order/OrderService.java";
        String price = "src/main/java/com/example/order/PriceCalculator.java";
        String repository = "src/main/java/com/example/order/OrderRepository.java";
        String controller = "src/main/java/com/example/order/OrderController.java";
        String auth = "src/main/java/com/example/auth/AuthService.java";

        List<MutationCase> core = List.of(
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
                        "return user.equals(password);", true, "security"),

                new MutationCase("boundary-off-by-one", price,
                        "        boolean bulk = quantity > 10;",
                        "        boolean bulk = quantity >= 10;",
                        "boolean bulk = quantity >= 10;", true, "boundary"),

                new MutationCase("negative-quantity-check-removed", price,
                        join("        if (quantity < 0) {",
                                "            throw new IllegalArgumentException(\"quantity must not be negative\");",
                                "        }"),
                        "", "return unitPrice * quantity;", true, "validation"),

                new MutationCase("guard-kept-but-logged", service,
                        join("        if (order == null) {",
                                "            throw new IllegalArgumentException(\"order must not be null\");",
                                "        }"),
                        join("        if (order == null) {",
                                "            log.warn(\"order is missing\");",
                                "            throw new IllegalArgumentException(\"order must not be null\");",
                                "        }"),
                        "log.warn(\"order is missing\");", false, "clean"),

                new MutationCase("case-insensitive-password", auth,
                        "        return user.equals(password);",
                        "        return user.equalsIgnoreCase(password);",
                        "equalsIgnoreCase", true, "security"),

                new MutationCase("password-logged", auth,
                        join("    public boolean login(String user, String password) {",
                                "        return check(user, password);"),
                        join("    public boolean login(String user, String password) {",
                                "        System.out.println(\"login attempt \" + user + \" / \" + password);",
                                "        return check(user, password);"),
                        "System.out.println(\"login attempt ", true, "semantic-pii"),

                new MutationCase("validation-after-use", service,
                        join("        validate(order);", "        repository.save(order);"),
                        join("        repository.save(order);", "        validate(order);"),
                        join("repository.save(order);", "        validate(order);"), true, "semantic-order"));
        List<MutationCase> all = new ArrayList<>(core);
        all.addAll(extraCases(service, price, repository, controller, auth));
        return all;
    }

    /** Additional mutations, applied after the core sequence, so later searches see earlier edits. */
    private static List<MutationCase> extraCases(String service, String price, String repository,
                                                 String controller, String auth) {
        List<MutationCase> cases = new ArrayList<>();

        cases.add(new MutationCase("audit-visibility-widened", service,
                "    private void audit(Order order) {",
                "    public void audit(Order order) {",
                "public void audit", true, "contract"));

        cases.add(new MutationCase("validate-guard-removed", service,
                join("        if (order == null) {",
                        "            log.warn(\"order is missing\");",
                        "            throw new IllegalArgumentException(\"order must not be null\");",
                        "        }"),
                "", "public void save", true, "consistency"));

        cases.add(new MutationCase("stub-comment-reworded", repository,
                "        // no-op stub",
                "        // still a stub", "still a stub", false, "clean"));

        cases.add(new MutationCase("tax-added-instead-of-subtracted", price,
                "        return amount + tax;",
                "        return amount - tax;", "amount - tax", true, "arithmetic"));

        cases.add(new MutationCase("discount-magic-number", price,
                "            return quantity * 9;",
                "            return quantity * 8;", "* 8;", true, "arithmetic"));

        cases.add(new MutationCase("discount-bulk-multiplier", price,
                "        return quantity * 10;",
                "        return quantity * 100;", "* 100;", true, "arithmetic"));

        cases.add(new MutationCase("auth-always-true", auth,
                "        return user.equalsIgnoreCase(password);",
                "        return true;", "return true;", true, "security"));

        cases.add(new MutationCase("handle-made-public", controller,
                "    private String handle(String id) {",
                "    public String handle(String id) {",
                "public String handle", true, "contract"));

        cases.add(new MutationCase("not-found-returns-null", controller,
                "            return \"not found\";",
                "            return null;", "return null;", true, "semantic"));

        cases.add(new MutationCase("null-added-to-collection", controller,
                "        names.add(\"second\");",
                "        names.add(null);", "names.add(null)", true, "semantic"));

        cases.add(new MutationCase("save-call-renamed", controller,
                "        service.save(order);",
                "        service.persist(order);", "service.persist", false, "clean"));

        cases.add(new MutationCase("list-fully-qualified", controller,
                "        List<String> names = new ArrayList<>();",
                "        List<String> names = new java.util.ArrayList<>();",
                "new java.util.ArrayList<>()", false, "clean"));

        cases.add(new MutationCase("log-message-reworded", service,
                "        log.info(\"audited order {}\", order.getName());",
                "        log.info(\"audited order {} v2\", order.getName());",
                "order {} v2", false, "clean"));

        cases.add(new MutationCase("shipping-zone-multiplier", service,
                "        int base = zone * 3;",
                "        int base = zone * 4;", "zone * 4", true, "arithmetic"));

        cases.add(new MutationCase("multiply-operands-swapped", price,
                "            return quantity * 8;",
                "            return 8 * quantity;", "8 * quantity", false, "clean"));

        cases.add(new MutationCase("field-commented", service,
                "    private final OrderRepository repository;",
                "    private final OrderRepository repository; // wired once",
                "// wired once", false, "clean"));

        cases.add(new MutationCase("require-nonnull-dropped", repository,
                "        this.root = Objects.requireNonNull(root);",
                "        this.root = root;", "this.root = root;", true, "consistency"));

        cases.add(new MutationCase("read-all-swallows-io", repository,
                "        return Files.readAllLines(file);",
                join("        try {",
                        "            return Files.readAllLines(file);",
                        "        } catch (IOException e) {",
                        "            return List.of();",
                        "        }"),
                "catch (IOException e)", true, "exception"));

        cases.add(new MutationCase("password-content-equals", auth,
                "        return true;",
                "        return user.contentEquals(password);",
                "contentEquals", true, "security"));

        cases.add(new MutationCase("submit-zone-parameter-dropped", controller,
                "    public String submit(Order order, int zone) {",
                "    public String submit(Order order) {",
                "public String submit(Order order) {", true, "contract"));

        cases.add(new MutationCase("line-total-guard-added", price,
                "        return unitPrice * quantity;",
                join("        if (quantity < 0) {",
                        "            return 0;",
                        "        }",
                        "        return unitPrice * quantity;"),
                "quantity < 0", false, "clean"));

        cases.add(new MutationCase("list-method-commented", controller,
                "    public List<String> list() {",
                join("    // names in display order",
                        "    public List<String> list() {"),
                "// names in display order", false, "clean"));

        cases.add(new MutationCase("unused-import-added", repository,
                "import java.util.Objects;",
                join("import java.util.Objects;", "import java.util.Map;"),
                "import java.util.Map;", false, "clean"));

        cases.add(new MutationCase("trailing-whitespace", controller,
                "        return \"ok\";",
                "        return \"ok\"; ", "return \"ok\";", false, "clean"));

        cases.add(new MutationCase("tax-multiplied", price,
                "        return amount - tax;",
                "        return amount * tax;", "amount * tax", true, "arithmetic"));

        // Two changes that no shape rule decides: the violation is written down in docs/policies
        // rather than visible in the syntax. They exist to measure what policy retrieval adds.
        cases.add(new MutationCase("policy-plain-equals", auth,
                "        return user.contentEquals(password);",
                "        return user.equals(password);",
                "return user.equals(password);", true, "policy-security"));

        cases.add(new MutationCase("policy-stdout-in-audit", service,
                "        log.info(\"audited order {} v2\", order.getName());",
                "        System.out.println(\"audited order \" + order.getName());",
                "System.out.println(\"audited order", true, "policy-logging"));

        return cases;
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
                "",
                "    public int lineTotal(int unitPrice, int quantity) {",
                "        if (quantity < 0) {",
                "            throw new IllegalArgumentException(\"quantity must not be negative\");",
                "        }",
                "        return unitPrice * quantity;",
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
