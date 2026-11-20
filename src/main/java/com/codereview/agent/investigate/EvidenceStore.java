package com.codereview.agent.investigate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every result a tool handed back during one unit's investigation.
 *
 * <p>Ids are assigned here and nowhere else, in arrival order, so "ev-3" means the same thing in
 * the trace, in the model's next prompt and in the finding it eventually cites.
 */
public final class EvidenceStore {

    private final List<Evidence> entries = new ArrayList<>();

    /**
     * @return the stored evidence, whose id the caller can hand to the model
     */
    public Evidence add(String tool, String file, int startLine, int endLine, String text) {
        Evidence evidence = new Evidence("ev-" + (entries.size() + 1), tool, file, startLine, endLine, text);
        entries.add(evidence);
        return evidence;
    }

    public Optional<Evidence> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return entries.stream().filter(evidence -> evidence.id().equals(id)).findFirst();
    }

    public List<Evidence> all() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }
}
