package com.codereview.agent.investigate;

import java.util.List;

/**
 * A conclusion that survived verification: a statement about one place, backed by evidence ids.
 */
public record Finding(String unitKey, String file, int line, String statement, List<String> evidenceIds) {

    public Finding {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public String describe() {
        return file + ":" + line + " — " + statement + " [" + String.join(", ", evidenceIds) + "]";
    }
}
