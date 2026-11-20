package com.codereview.agent.investigate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verifier is the part that makes "evidence-gated" true rather than aspirational, so each rule
 * gets a test that fails if the rule is removed.
 */
class FindingVerifierTest {

    private final FindingVerifier verifier = new FindingVerifier();

    private EvidenceStore storeWithGuard() {
        EvidenceStore store = new EvidenceStore();
        store.add("read_lines", "a/Login.java", 10, 14,
                "10: if (user == null) {\n11:     return null;\n12: }\n13: token = refresh(user);");
        return store;
    }

    @Test
    void acceptsAFindingWhoseLineAndSymbolsAreCoveredByCitedEvidence() {
        EvidenceStore store = storeWithGuard();
        Finding finding = new Finding("u1", "a/Login.java", 12,
                "这里直接返回了 `user` 的空值，调用方需要处理。", List.of("ev-1"));

        FindingVerifier.Validation result = verifier.verify(finding, store);

        assertThat(result.ok()).isTrue();
    }

    @Test
    void rejectsAFindingThatCitesAnIdItNeverReceived() {
        EvidenceStore store = storeWithGuard();
        Finding finding = new Finding("u1", "a/Login.java", 12, "……", List.of("ev-9"));

        FindingVerifier.Validation result = verifier.verify(finding, store);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("does not exist");
    }

    @Test
    void rejectsALineOutsideTheCitedEvidence() {
        EvidenceStore store = storeWithGuard();
        Finding finding = new Finding("u1", "a/Login.java", 40, "……", List.of("ev-1"));

        FindingVerifier.Validation result = verifier.verify(finding, store);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("not covered");
    }

    @Test
    void rejectsASymbolTheEvidenceNeverShows() {
        EvidenceStore store = storeWithGuard();
        Finding finding = new Finding("u1", "a/Login.java", 12,
                "`auditLog` 没有被调用。", List.of("ev-1"));

        FindingVerifier.Validation result = verifier.verify(finding, store);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("auditLog");
    }

    @Test
    void rejectsAFindingWithNoEvidenceAtAll() {
        Finding finding = new Finding("u1", "a/Login.java", 12, "看起来有问题。", List.of());

        assertThat(verifier.verify(finding, storeWithGuard()).ok()).isFalse();
    }

    @Test
    void extractsEveryBacktickedSymbolInOrder() {
        assertThat(FindingVerifier.backticked("`a` 与 `b.c` 不一致"))
                .containsExactly("a", "b.c");
    }
}
