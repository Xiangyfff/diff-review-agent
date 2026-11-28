package com.codereview.agent.triage.policy;

import java.util.List;

/**
 * One retrievable piece of a policy document: a heading and the text under it.
 *
 * <p>The id is what a conclusion may cite, so it is short, stable and never reused. It lives in
 * the {@code pol-} namespace on purpose: a policy excerpt is not code evidence, and the two must
 * never be confused by the verifier.
 *
 * <p>A clause is only <em>enforceable</em> when its heading says it is mandatory and nothing marks
 * it as advisory. An enforceable clause may raise a tier on its own. A clause marked
 * {@code [参考]} is background: it can be quoted in a conclusion, but it can neither gate a tier
 * nor exempt one, because a rule nobody can point at a concrete case for is not a rule yet.
 *
 * <p>An <em>allowance</em> clause is the document saying "this case is fine". It has to name the
 * rule ids it exempts, the paths it applies to, and at least one example, and all three have to
 * agree with the unit before it is used. That is deliberately stricter than gating: a wrong
 * escalation costs one investigation, while a wrong exemption hides a real defect.
 *
 * @param mandatory     the heading was marked as mandatory, which is the document author saying
 *                      "a change touching this area has to be checked", not the model's opinion
 * @param advisory      the heading was marked as advisory; it can never gate or exempt
 * @param allowance     the heading was marked as allowing something; used for exemptions only
 * @param exemptedRules rule ids this allowance clause can exempt (empty for ordinary clauses)
 * @param scope         path patterns the allowance applies to
 * @param examples      fenced code samples under the section, both wrong and right ones. They
 *                      exist because a change is code: matching code against prose is a
 *                      cross-modal guess, matching code against code is not.
 */
public record PolicyChunk(String id, String file, String heading, String text, boolean mandatory,
                          boolean advisory, boolean allowance, List<String> exemptedRules,
                          List<String> scope, List<String> examples) {

    public PolicyChunk {
        exemptedRules = exemptedRules == null ? List.of() : List.copyOf(exemptedRules);
        scope = scope == null ? List.of() : List.copyOf(scope);
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    /** A section without samples still retrieves on its prose alone. */
    public PolicyChunk(String id, String file, String heading, String text, boolean mandatory) {
        this(id, file, heading, text, mandatory, false, false, List.of(), List.of(), List.of());
    }

    /** Kept for callers that already carry samples but predate the tier markers. */
    public PolicyChunk(String id, String file, String heading, String text, boolean mandatory,
                       List<String> examples) {
        this(id, file, heading, text, mandatory, false, false, List.of(), List.of(), examples);
    }

    /** The tier the document declares for this clause. */
    public String tier() {
        if (advisory) {
            return "referenced";
        }
        return enforceable() ? "enforceable" : "background";
    }

    /** Only an enforceable clause may raise a tier. */
    public boolean enforceable() {
        return mandatory && !advisory && !allowance;
    }

    /** An allowance needs all three: rules, scope, and an example to match the change against. */
    public boolean canExempt() {
        return allowance && !exemptedRules.isEmpty() && !scope.isEmpty() && !examples.isEmpty();
    }

    /** How a citation reads in a prompt or a comment. */
    public String cite() {
        return id + " " + file + " / " + heading;
    }

    /**
     * The clause as a reader of the document sees it: the prose plus the fenced examples under
     * the same heading. An investigator that only gets the prose is told what is forbidden but
     * not what the document itself proposes instead, and then any suggestion it makes from
     * general knowledge looks invented to the verifier.
     */
    public String fullText() {
        if (examples.isEmpty()) {
            return text;
        }
        StringBuilder all = new StringBuilder(text);
        for (String example : examples) {
            all.append("\n\n```\n").append(example.strip()).append("\n```");
        }
        return all.toString();
    }

    /** A bounded excerpt for prompts, so one long section cannot swallow the budget. */
    public String excerpt(int width) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= width ? flat : flat.substring(0, width) + "...";
    }
}
