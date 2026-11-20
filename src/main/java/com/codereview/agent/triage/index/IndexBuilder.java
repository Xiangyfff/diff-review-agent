package com.codereview.agent.triage.index;

import com.codereview.agent.triage.parse.SourceParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.SimpleName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Scans a repository once and produces a {@link SymbolIndex}.
 *
 * <p>The scan is deliberately quiet: unreadable or unparsable files are counted and skipped, not
 * fatal, because the index is a hint and one broken file must not take down a review run. Build
 * output and dependency directories are skipped, and the scan stops at a file cap so a
 * pathological repository cannot make triage hang.
 */
public final class IndexBuilder {

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".idea", ".gradle", ".mvn", "target", "build", "out", "bin",
            "node_modules", "generated", "generated-sources", "test-repos");

    private static final long MAX_FILE_BYTES = 1_000_000L;
    private static final int MAX_FILES = 20_000;

    private final SourceParser parser;

    public IndexBuilder(SourceParser parser) {
        this.parser = parser;
    }

    public Result build(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return new Result(SymbolIndex.empty(), 0, 0, false);
        }

        Map<String, Set<String>> filesBySymbol = new HashMap<>();
        Map<String, SymbolIndex.Declaration> declarations = new LinkedHashMap<>();
        int[] counters = new int[2];
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (directory.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
                return SKIPPED_DIRECTORIES.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (counters[0] >= MAX_FILES) {
                    return FileVisitResult.TERMINATE;
                }
                String name = file.getFileName().toString();
                if (!name.endsWith(".java") || attributes.size() > MAX_FILE_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                counters[0]++;
                Optional<CompilationUnit> parsed;
                try {
                    parsed = parser.parseComplete(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException unreadable) {
                    counters[1]++;
                    return FileVisitResult.CONTINUE;
                }
                if (parsed.isEmpty()) {
                    counters[1]++;
                    return FileVisitResult.CONTINUE;
                }
                collect(root, file, parsed.get(), filesBySymbol, declarations);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                counters[1]++;
                return FileVisitResult.CONTINUE;
            }
        });
        boolean truncated = counters[0] >= MAX_FILES;
        SymbolIndex index = new SymbolIndex(filesBySymbol, declarations, counters[0], counters[1], truncated);
        return new Result(index, counters[0], counters[1], truncated);
    }

    private static void collect(Path root, Path file, CompilationUnit unit,
                                Map<String, Set<String>> filesBySymbol,
                                Map<String, SymbolIndex.Declaration> declarations) {
        String relative = relativePath(root, file);
        for (SimpleName name : unit.findAll(SimpleName.class)) {
            filesBySymbol.computeIfAbsent(name.asString(), symbol -> new HashSet<>()).add(relative);
        }
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            record(declarations, type.getNameAsString(), relative,
                    type.getBegin().map(position -> position.line).orElse(0), isPublic(type.getModifiers()));
        }
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            record(declarations, method.getNameAsString(), relative,
                    method.getBegin().map(position -> position.line).orElse(0), isPublic(method.getModifiers()));
        }
        for (ConstructorDeclaration constructor : unit.findAll(ConstructorDeclaration.class)) {
            record(declarations, constructor.getNameAsString(), relative,
                    constructor.getBegin().map(position -> position.line).orElse(0),
                    isPublic(constructor.getModifiers()));
        }
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            boolean publiclyDeclared = isPublic(field.getModifiers());
            int line = field.getBegin().map(position -> position.line).orElse(0);
            field.getVariables().forEach(variable ->
                    record(declarations, variable.getNameAsString(), relative, line, publiclyDeclared));
        }
    }

    private static void record(Map<String, SymbolIndex.Declaration> declarations, String symbol,
                               String file, int line, boolean publiclyDeclared) {
        SymbolIndex.Declaration existing = declarations.get(symbol);
        if (existing == null) {
            declarations.put(symbol, new SymbolIndex.Declaration(symbol, file, line, publiclyDeclared, 1));
        } else {
            declarations.put(symbol, new SymbolIndex.Declaration(
                    symbol, existing.file(), existing.line(), existing.publiclyDeclared(), existing.count() + 1));
        }
    }

    private static boolean isPublic(java.util.List<Modifier> modifiers) {
        return modifiers.stream().anyMatch(modifier -> modifier.getKeyword() == Modifier.Keyword.PUBLIC);
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public record Result(SymbolIndex index, int filesScanned, int parseFailures, boolean truncated) {
    }
}
