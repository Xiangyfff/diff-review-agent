package com.codereview.agent.verify;

import java.util.List;

/**
 * What the critic decided about one finding. A retraction or a downgrade has to point at evidence
 * the critic actually collected in this round, for the same reason a finding has to: an opinion is
 * not a refutation.
 */
public sealed interface Verdict {

    String reason();

    List<String> counterEvidenceIds();

    /** Nothing refuted the finding; it is published as a statement. */
    record Keep(String reason) implements Verdict {
        @Override
        public List<String> counterEvidenceIds() {
            return List.of();
        }
    }

    /** The finding survives but becomes a question instead of an assertion. */
    record Downgrade(String reason, List<String> counterEvidenceIds) implements Verdict {
    }

    /** The counter evidence is strong enough that the finding is dropped. */
    record Retract(String reason, List<String> counterEvidenceIds) implements Verdict {
    }
}
