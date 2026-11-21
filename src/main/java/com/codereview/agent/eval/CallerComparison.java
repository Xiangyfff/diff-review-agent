package com.codereview.agent.eval;

import com.codereview.agent.investigate.graph.CallGraph;
import com.codereview.agent.triage.index.IndexBuilder;
import com.codereview.agent.triage.index.SymbolIndex;
import com.codereview.agent.triage.parse.SourceParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares the two ways of answering "who calls this": the name index and the resolved call graph.
 *
 * <p>This is the measurement behind the claim that type resolution is worth its cost. It needs no
 * model, so it can be run on any repository at any time; the numbers are counts of files and call
 * sites, not judgements.
 */
public final class CallerComparison {

    private static final int MAX_METHODS = 300;

    private CallerComparison() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: CallerComparison <repository> [output.md]");
            System.exit(2);
        }
        Path repository = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = args.length > 1 ? Path.of(args[1]) : null;

        SymbolIndex index = new IndexBuilder(new SourceParser()).build(repository).index();
        CallGraph graph = new CallGraph(repository);

        List<Row> rows = new ArrayList<>();
        int nameOnly = 0;
        long nameFiles = 0;
        long callSites = 0;
        for (Path source : sources(repository)) {
            if (rows.size() >= MAX_METHODS) {
                break;
            }
            CompilationUnit unit = new SourceParser().parse(Files.readString(source)).orElse(null);
            if (unit == null) {
                continue;
            }
            String relative = repository.relativize(source).toString().replace('\\', '/');
            for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
                if (rows.size() >= MAX_METHODS) {
                    break;
                }
                int line = method.getBegin().map(position -> position.line).orElse(0);
                String name = method.getNameAsString();
                List<String> files = index.filesMentioning(name).stream().sorted().toList();
                List<CallGraph.Call> callers = graph.callersOf(relative, line, name);
                boolean noisy = !files.isEmpty() && callers.isEmpty();
                nameOnly += noisy ? files.size() : 0;
                nameFiles += files.size();
                callSites += callers.size();
                rows.add(new Row(relative + ":" + line, name, files.size(), callers.size(), noisy));
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("| method | name-level files | resolved call sites | name-only |\n");
        report.append("| --- | --- | --- | --- |\n");
        for (Row row : rows) {
            report.append("| ").append(row.where()).append(' ').append(row.name()).append(" | ")
                    .append(row.nameFiles()).append(" | ").append(row.callers()).append(" | ")
                    .append(row.noisy() ? "yes" : "").append(" |\n");
        }
        report.append('\n').append("methods measured: ").append(rows.size())
                .append("; name-level file mentions: ").append(nameFiles)
                .append("; resolved call sites: ").append(callSites)
                .append("; mentions from methods nobody calls: ").append(nameOnly).append('\n');

        System.out.print(report);
        if (output != null) {
            Files.createDirectories(output.getParent());
            Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        }
    }

    private static List<Path> sources(Path repository) throws IOException {
        Path root = Files.isDirectory(repository.resolve("src/main/java"))
                ? repository.resolve("src/main/java")
                : repository;
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .limit(MAX_METHODS * 4)
                    .sorted()
                    .toList();
        }
    }

    private record Row(String where, String name, int nameFiles, int callers, boolean noisy) {
    }
}
