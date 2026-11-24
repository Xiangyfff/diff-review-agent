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
    void acceptsASymbolWrittenWithItsParameterList() {
        EvidenceStore store = new EvidenceStore();
        store.add("read_lines", "a/OrderService.java", 20, 22, "20: public void save(Order order) {\n21:     validate(order);");
        Finding finding = new Finding("u1", "a/OrderService.java", 21,
                "`save(Order)` 不再带事务注解，异常回滚行为会变。", List.of("ev-1"));

        FindingVerifier.Validation result = verifier.verify(finding, store);

        assertThat(result.ok()).isTrue();
    }

    @Test
    void acceptsASymbolThatOnlyTheDiffStillMentions() {
        EvidenceStore store = new EvidenceStore();
        store.add("read_lines", "a/OrderService.java", 20, 21, "20: public void save(Order order) {\n21:     validate(order);");
        Finding finding = new Finding("u1", "a/OrderService.java", 21,
                "`@Transactional` 被移除了，异常时不会再回滚。", List.of("ev-1"));

        FindingVerifier.Validation result = verifier.verify(finding, store, 20, 21,
                "@Transactional\npublic void save(Order order) {\n    validate(order);");

        assertThat(result.ok()).isTrue();
    }

    @Test
    void aSuggestionRebuiltFromTheLineTheEvidenceShowsIsNotAnInventedName() {
        EvidenceStore store = new EvidenceStore();
        store.add("read_lines", "a/OrderService.java", 43, 45,
                "43: public void audit(Order order) {\n44:     System.out.println(\"audited order \" + order.getName());\n45: }");
        Finding finding = new Finding("u1", "a/OrderService.java", 44,
                "这里把 `log.info` 换成了 `System.out.println`，请改回 "
                        + "`log.info(\"audited order {}\", order.getName())`。", List.of("ev-1"));
        String before = "public void audit(Order order) {\n"
                + "    log.info(\"audited order {} v2\", order.getName());\n}";

        FindingVerifier.Validation result = verifier.verify(finding, store, 43, 45, before);

        assertThat(result.ok()).isTrue();
    }

    @Test
    void rejectsAFindingWithNoEvidenceAtAll() {
        Finding finding = new Finding("u1", "a/Login.java", 12, "看起来有问题。", List.of());

        assertThat(verifier.verify(finding, storeWithGuard()).ok()).isFalse();
    }

    @Test
    void rejectsAConclusionThatOnlyRestatesTheChange() {
        Finding finding = new Finding("u1", "a/Login.java", 12, "签名发生了变化。", List.of("ev-1"),
                "whatever");

        assertThat(verifier.verify(finding, storeWithGuard()).ok()).isFalse();
        assertThat(verifier.verify(finding, storeWithGuard()).reason()).contains("demand");
    }

    @Test
    void extractsEveryBacktickedSymbolInOrder() {
        assertThat(FindingVerifier.backticked("`a` 与 `b.c` 不一致"))
                .containsExactly("a", "b.c");
    }

    @Test
    void aConclusionThatCitesAPolicyClauseMustQuoteItsWording() {
        Finding finding = new Finding("u1", "a/A.java", 10,
                "这里违反了口令必须使用恒定时间的比较方式这条规定，请改用恒定时间比较。",
                java.util.List.of("pol-security-2"), Finding.FIX);
        FindingVerifier.Validation ok = new FindingVerifier().verify(finding,
                new EvidenceStore(), 1, 20, "",
                java.util.Map.of("pol-security-2", "口令必须使用恒定时间的比较方式，禁止使用 equals。"));
        assertThat(ok.ok()).isTrue();

        Finding vague = new Finding("u1", "a/A.java", 10,
                "这里违反了安全规范，请改掉。",
                java.util.List.of("pol-security-2"), Finding.FIX);
        FindingVerifier.Validation rejected = new FindingVerifier().verify(vague,
                new EvidenceStore(), 1, 20, "",
                java.util.Map.of("pol-security-2", "口令必须使用恒定时间的比较方式，禁止使用 equals。"));
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.reason()).contains("does not quote");
    }

    @Test
    void aPolicyIdThatTheRepositoryDoesNotHaveIsRejected() {
        Finding finding = new Finding("u1", "a/A.java", 10,
                "口令必须使用恒定时间的比较方式。", java.util.List.of("pol-made-up-9"), Finding.FIX);
        FindingVerifier.Validation rejected = new FindingVerifier().verify(finding,
                new EvidenceStore(), 1, 20, "", java.util.Map.of());
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.reason()).contains("does not exist");
    }

    @Test
    void aClauseNamedOnlyInTheStatementStillHasToBeQuoted() {
        // Observed on a local model: it wrote "不符合 pol-logging-1" in the statement but cited
        // only ev-1 as evidence, which used to skip the wording check entirely.
        Finding finding = new Finding("u1", "a/Login.java", 12,
                "这里使用了 System.out，不符合 pol-logging-1 的规定。",
                java.util.List.of("ev-1"), Finding.FIX);
        FindingVerifier.Validation rejected = verifier.verify(finding, storeWithGuard(), 10, 14, "",
                java.util.Map.of("pol-logging-1", "生产代码使用日志框架，不允许 System.out 或 System.err。"));
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.reason()).contains("does not quote");
    }

    @Test
    void sharingOnlyTheCodeMentionedByTheClauseIsNotAQuote() {
        // The clause happens to spell System.out, and so does the statement. Sharing that text is
        // not quoting the clause; the sentence still never says what the clause demands.
        Finding finding = new Finding("u1", "a/Login.java", 12,
                "这里直接调用 System.out.println，不符合 pol-logging-1 的规定。",
                java.util.List.of("ev-1"), Finding.FIX);
        FindingVerifier.Validation rejected = verifier.verify(finding, storeWithGuard(), 10, 14, "",
                java.util.Map.of("pol-logging-1", "生产代码使用日志框架，不允许 System.out 或 System.err。"));
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.reason()).contains("does not quote");
    }

    @Test
    void aClauseNamedOnlyInTheStatementIsAcceptedWhenItsWordingIsQuoted() {
        Finding finding = new Finding("u1", "a/Login.java", 12,
                "这里直接返回空值，且违反 pol-logging-1「生产代码使用日志框架，不允许 System.out 或 System.err」。",
                java.util.List.of("ev-1"), Finding.FIX);
        FindingVerifier.Validation accepted = verifier.verify(finding, storeWithGuard(), 10, 14, "",
                java.util.Map.of("pol-logging-1", "生产代码使用日志框架，不允许 System.out 或 System.err。"));
        assertThat(accepted.ok()).isTrue();
    }
}
