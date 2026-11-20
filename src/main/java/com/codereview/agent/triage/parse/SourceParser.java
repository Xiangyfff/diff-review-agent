package com.codereview.agent.triage.parse;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;

import java.util.Optional;

/**
 * Single entry point for turning Java source into a syntax tree.
 *
 * <p>One configuration for the whole pipeline, so a file is never parsed twice with
 * different settings. Comments and raw tokens are dropped: the triage stage reasons about
 * structure, and keeping comments attached would only introduce noise into the
 * before/after comparison.
 */
public final class SourceParser {

    private static final String MEMBER_WRAPPER_OPEN = "class __ReviewUnit {\n";
    private static final String MEMBER_WRAPPER_CLOSE = "\n}\n";

    private final ParserConfiguration configuration;

    public SourceParser() {
        // Tokens must be kept: JavaParser derives every node's begin/end from the token range,
        // so disabling token storage leaves the AST without positions and unit mapping collapses.
        this.configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);
    }

    public Optional<CompilationUnit> parse(String source) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        ParseResult<CompilationUnit> result = new JavaParser(configuration).parse(source);
        return result.getResult();
    }

    /**
     * Parses only when the whole file was understood.
     *
     * <p>The triage stage tolerates a partially parsed unit, because half a method still has a
     * position and a shape. The repository index cannot: a file that only half parsed would
     * contribute half its symbols and quietly understate how widely a name is used, so it is
     * counted as a failure instead.
     */
    public Optional<CompilationUnit> parseComplete(String source) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        ParseResult<CompilationUnit> result = new JavaParser(configuration).parse(source);
        return result.isSuccessful() ? result.getResult() : Optional.empty();
    }

    /**
     * Parses a single member that was lifted out of its file (a method, a field, an
     * initializer block).
     *
     * <p>Re-parsing is deliberate. Units are stored as plain text so they stay
     * serialisable and comparable, and one member is cheap to parse. The wrapper class is
     * the smallest legal host for a member; it adds exactly one line, which callers must
     * not use for line arithmetic because the member text is pretty-printed anyway.
     */
    public Optional<BodyDeclaration<?>> parseMember(String memberText) {
        if (memberText == null || memberText.isBlank()) {
            return Optional.empty();
        }
        return parse(MEMBER_WRAPPER_OPEN + memberText + MEMBER_WRAPPER_CLOSE)
                .filter(cu -> !cu.getTypes().isEmpty())
                .map(cu -> cu.getType(0))
                .filter(type -> !type.getMembers().isEmpty())
                .map(type -> type.getMembers().get(0));
    }
}
