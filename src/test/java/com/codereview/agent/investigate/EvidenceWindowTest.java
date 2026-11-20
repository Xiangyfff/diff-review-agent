package com.codereview.agent.investigate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceWindowTest {

    @Test
    void keepsTheNewestEntriesAndFoldsTheRest() {
        List<Evidence> evidence = evidence(10);
        List<EvidenceWindow.Line> lines = EvidenceWindow.shape(evidence, Set.of(), 3);

        assertThat(lines).hasSize(10);
        assertThat(lines.subList(0, 7)).allMatch(EvidenceWindow.Line::folded);
        assertThat(lines.subList(7, 10)).noneMatch(EvidenceWindow.Line::folded);
    }

    @Test
    void aPinnedEntryStaysVerbatimHoweverOldItIs() {
        List<Evidence> evidence = evidence(10);
        List<EvidenceWindow.Line> lines = EvidenceWindow.shape(evidence, Set.of("ev-1"), 3);

        assertThat(lines.get(0).folded()).isFalse();
        assertThat(lines.get(0).text()).contains("line 1 of file 1");
    }

    @Test
    void aShortStoreIsLeftAlone() {
        assertThat(EvidenceWindow.foldedCount(evidence(4), Set.of(), 6)).isZero();
        assertThat(EvidenceWindow.foldedCount(evidence(10), Set.of(), 6)).isEqualTo(4);
    }

    @Test
    void rendersTheIdAndTheLocationOfAFoldedEntry() {
        String rendered = EvidenceWindow.render(evidence(10), Set.of(), 4);

        assertThat(rendered).contains("ev-1");
        assertThat(rendered).contains("已折叠");
        assertThat(rendered).doesNotContain("second line 2");
        assertThat(rendered.split("- ev-", -1)).hasSize(11);
    }

    @Test
    void keepsTheOriginalOrder() {
        List<EvidenceWindow.Line> lines = EvidenceWindow.shape(evidence(8), Set.of(), 2);
        assertThat(lines).extracting(line -> line.evidence().id())
                .containsExactly("ev-1", "ev-2", "ev-3", "ev-4", "ev-5", "ev-6", "ev-7", "ev-8");
    }

    @Test
    void anEmptyStoreRendersNothing() {
        assertThat(EvidenceWindow.render(List.of(), Set.of(), 6)).isEmpty();
    }

    private static List<Evidence> evidence(int count) {
        List<Evidence> items = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            String text = "line " + index + " of file " + index + "\nsecond line " + index;
            items.add(new Evidence("ev-" + index, "read_lines", "src/F" + index + ".java",
                    1, 2, text));
        }
        return items;
    }
}
