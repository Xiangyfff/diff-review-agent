package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;
import com.codereview.agent.triage.parse.SourceParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Answers whether a declaration can be reached from outside the repository.
 *
 * <p>This is the cheap fact that turns "no caller in this repository" from a dead end into a
 * decision: a {@code public} method on a {@code public} type may be called by code we cannot see, so
 * a contract change there is a real risk even when the call graph is empty. A package-private or
 * private method can only be called from inside, and an empty call graph really does mean nobody
 * uses it.
 */
public final class PublicSurfaceTool implements ReviewTool {

    private final RepoAccess repository;
    private final SourceParser parser;

    public PublicSurfaceTool(RepoAccess repository) {
        this.repository = repository;
        this.parser = new SourceParser();
    }

    @Override
    public String name() {
        return "public_surface";
    }

    @Override
    public String purpose() {
        return "Say whether a declaration is reachable from outside the repository: a public method "
                + "on a public type can have callers this repository never shows.";
    }

    @Override
    public String arguments() {
        return "file (repository-relative), symbol (declaration name), line (a line inside it)";
    }

    @Override
    public ToolOutcome run(ToolCall call) {
        String file = call.argument("file");
        String symbol = call.argument("symbol");
        int line = call.intArgument("line", 0);
        if (file.isEmpty() || symbol.isEmpty()) {
            return ToolOutcome.failed("file and symbol are required");
        }
        if (repository.resolve(file).isEmpty()) {
            return ToolOutcome.failed(file + " is not a file inside the repository");
        }
        String source = String.join("\n", repository.readLines(file));
        Optional<CompilationUnit> unit = parser.parse(source);
        if (unit.isEmpty()) {
            return ToolOutcome.failed(file + " does not parse, so its modifiers cannot be read");
        }
        Optional<Node> declaration = declarationAt(unit.get(), symbol, line);
        if (declaration.isEmpty()) {
            return ToolOutcome.failed("no declaration named " + symbol + " contains line " + line
                    + " in " + file);
        }
        Node found = declaration.get();
        String own = visibility(found);
        List<String> enclosing = new ArrayList<>();
        Node current = found.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                enclosing.add(type.getNameAsString() + " is " + visibility(type));
            }
            current = current.getParentNode().orElse(null);
        }
        boolean external = own.equals("public") && enclosing.stream()
                .allMatch(text -> text.contains(" is public"));
        String verdict = external
                ? "reachable from outside the repository: callers outside this checkout are invisible here"
                : (own.equals("public") || own.equals("protected"))
                        ? "public inside a non-public type: callers are limited to this package or subclasses"
                        : "not part of the external surface: only code inside this repository can call it";
        int at = found.getRange().map(range -> range.begin.line).orElse(line);
        String signature = found.getChildNodes().isEmpty() ? symbol : found.toString().strip();
        ToolOutcome.Excerpt excerpt = new ToolOutcome.Excerpt(file, at, at,
                at + ": " + firstLine(signature));
        return ToolOutcome.of(symbol + " is " + own + " on " + describe(enclosing) + "; " + verdict,
                List.of(excerpt));
    }

    private Optional<Node> declarationAt(CompilationUnit unit, String symbol, int line) {
        return unit.findAll(BodyDeclaration.class).stream()
                .filter(declaration -> nameOf(declaration).equals(symbol))
                .filter(declaration -> declaration.getRange().isPresent())
                .filter(declaration -> declaration.getRange().get().begin.line <= line
                        && declaration.getRange().get().end.line >= line)
                .<Node>map(declaration -> declaration)
                .findFirst();
    }

    private static String nameOf(BodyDeclaration<?> declaration) {
        if (declaration instanceof TypeDeclaration<?> type) {
            return type.getNameAsString();
        }
        if (declaration instanceof com.github.javaparser.ast.body.MethodDeclaration method) {
            return method.getNameAsString();
        }
        if (declaration instanceof com.github.javaparser.ast.body.ConstructorDeclaration constructor) {
            return constructor.getNameAsString();
        }
        if (declaration instanceof com.github.javaparser.ast.body.FieldDeclaration field) {
            return field.getVariables().isEmpty() ? "" : field.getVariable(0).getNameAsString();
        }
        return "";
    }

    private static String visibility(Node node) {
        if (!(node instanceof NodeWithModifiers<?> withModifiers)) {
            return "package-private";
        }
        if (withModifiers.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PUBLIC)) {
            return "public";
        }
        if (withModifiers.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PROTECTED)) {
            return "protected";
        }
        if (withModifiers.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PRIVATE)) {
            return "private";
        }
        return "package-private";
    }

    private static String describe(List<String> enclosing) {
        return enclosing.isEmpty() ? "a top-level declaration" : String.join(", ", enclosing);
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String head = newline < 0 ? text : text.substring(0, newline);
        return head.strip().length() <= 160 ? head.strip() : head.strip().substring(0, 160);
    }
}
