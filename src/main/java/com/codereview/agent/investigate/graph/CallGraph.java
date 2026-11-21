package com.codereview.agent.investigate.graph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Type-level call graph over the repository under review.
 *
 * <p>The name index answers "who mentions this name"; this answers "which calls actually resolve to
 * this declaration". The difference matters when two classes have a method with the same name, when
 * an interface call dispatches to an implementation, or when a call goes through a different
 * overload. Resolution costs a compile-like pass over the sources, so it is built on demand and
 * only for the units that are being investigated.
 *
 * <p>A call that cannot be resolved is not reported as a caller: guessing would put a number in the
 * report that the evidence cannot back.
 */
public final class CallGraph {

    private static final Set<String> SKIPPED = Set.of(
            ".git", ".idea", ".gradle", ".mvn", "target", "build", "out", "bin", "node_modules");

    private static final int MAX_FILES = 4000;
    private static final int MAX_CALLERS = 8;
    private static final int MAX_SNIPPET = 160;

    private final Path root;
    private final JavaParser parser;

    public CallGraph(Path root) {
        this.root = root.toAbsolutePath().normalize();
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver());
        for (Path sourceRoot : sourceRoots()) {
            solver.add(new JavaParserTypeSolver(sourceRoot));
        }
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false)
                .setSymbolResolver(new JavaSymbolSolver(solver));
        this.parser = new JavaParser(configuration);
    }

    /**
     * @param file       repository-relative path of the file declaring the method
     * @param line       a line inside the declaration, used to pick the right overload
     * @param methodName simple name, used to narrow the search before resolution
     */
    public List<Call> callersOf(String file, int line, String methodName) {
        Optional<String> signature = signatureAt(file, line, methodName);
        if (signature.isEmpty()) {
            return List.of();
        }
        String target = signature.get();
        List<Call> callers = new ArrayList<>();
        for (Path source : sources()) {
            if (!parse(source).isPresent()) {
                continue;
            }
            CompilationUnit unit = parse(source).get();
            String relative = root.relativize(source).toString().replace('\\', '/');
            for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
                String resolved;
                try {
                    resolved = call.resolve().getQualifiedSignature();
                } catch (RuntimeException unresolved) {
                    continue;
                }
                if (!resolved.equals(target)) {
                    continue;
                }
                int at = call.getBegin().map(position -> position.line).orElse(0);
                callers.add(new Call(relative, at, snippet(call.toString())));
                if (callers.size() == MAX_CALLERS) {
                    return callers;
                }
            }
        }
        return callers;
    }

    private Optional<String> signatureAt(String file, int line, String methodName) {
        Path path = root.resolve(file).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        Optional<CompilationUnit> unit = parse(path);
        if (unit.isEmpty()) {
            return Optional.empty();
        }
        return unit.get().findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals(methodName))
                .filter(method -> method.getBegin().isPresent() && method.getEnd().isPresent())
                .filter(method -> method.getBegin().get().line <= line
                        && method.getEnd().get().line >= line)
                .findFirst()
                .flatMap(method -> {
                    try {
                        return Optional.of(method.resolve().getQualifiedSignature());
                    } catch (RuntimeException unresolved) {
                        return Optional.empty();
                    }
                });
    }

    private Optional<CompilationUnit> parse(Path path) {
        try {
            return parser.parse(path).getResult();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private List<Path> sources() {
        List<Path> files = new ArrayList<>();
        for (Path sourceRoot : sourceRoots()) {
            try {
                Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return SKIPPED.contains(dir.getFileName().toString())
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".java") && files.size() < MAX_FILES) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                return files;
            }
        }
        return files;
    }

    private List<Path> sourceRoots() {
        List<Path> roots = new ArrayList<>();
        for (String candidate : List.of("src/main/java", "src/test/java")) {
            Path path = root.resolve(candidate);
            if (Files.isDirectory(path)) {
                roots.add(path);
            }
        }
        return roots.isEmpty() ? List.of(root) : roots;
    }

    private static String snippet(String text) {
        String collapsed = text.replaceAll("\\s+", " ").strip();
        return collapsed.length() <= MAX_SNIPPET ? collapsed : collapsed.substring(0, MAX_SNIPPET);
    }

    /**
     * One place that calls the method, as reported to the model.
     */
    public record Call(String file, int line, String snippet) {
    }
}
