package com.codereview.agent.triage;

import com.codereview.agent.triage.parse.SourceParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;

/**
 * Parses the small source snippets the tests compare.
 *
 * <p>Uses the production parser on purpose: a test that parsed with a different
 * configuration would not prove anything about the code under test.
 */
public final class TestSource {

    private static final SourceParser PARSER = new SourceParser();

    private TestSource() {
    }

    public static CompilationUnit file(String source) {
        return PARSER.parse(source)
                .orElseThrow(() -> new AssertionError("test source does not parse:\n" + source));
    }

    public static BodyDeclaration<?> member(String memberText) {
        return PARSER.parseMember(memberText)
                .orElseThrow(() -> new AssertionError("test member does not parse:\n" + memberText));
    }
}
