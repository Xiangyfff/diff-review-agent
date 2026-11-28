package com.codereview.agent.investigate;

import java.util.List;

/**
 * What one unit's investigation produced, including the reasons it may have produced little.
 *
 * <p>The counts exist so a plan can report honestly: "twelve units, nine concluded, two ran out of
 * tool budget, one was abandoned with a stated reason" is a usable sentence, while "the agent
 * reviewed the diff" is not.
 */
public record InvestigationResult(
        String unitKey,
        String file,
        int line,
        List<Finding> findings,
        List<Abandoned> abandoned,
        int rejectedFindings,
        int toolCalls,
        int turns,
        List<Evidence> evidence,
        /** Conclusions of the form "the policy allows this"; recorded, and counted apart. */
        List<Exemption> exemptions,
        /** How every question ended, including the ones raised mid-investigation. */
        List<QuestionState> questions,
        Outcome outcome,
        /** One line per tool call, so the critic does not repeat what already failed. */
        List<String> attempts) {

    public InvestigationResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        abandoned = abandoned == null ? List.of() : List.copyOf(abandoned);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        exemptions = exemptions == null ? List.of() : List.copyOf(exemptions);
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    /** Kept for callers that predate deep-dive exemptions and self-raised questions. */
    public InvestigationResult(String unitKey, String file, int line, List<Finding> findings,
            List<Abandoned> abandoned, int rejectedFindings, int toolCalls, int turns,
            List<Evidence> evidence, Outcome outcome, List<String> attempts) {
        this(unitKey, file, line, findings, abandoned, rejectedFindings, toolCalls, turns, evidence,
                List.of(), List.of(), outcome, attempts);
    }

    /** Kept for callers that predate self-raised questions. */
    public InvestigationResult(String unitKey, String file, int line, List<Finding> findings,
            List<Abandoned> abandoned, int rejectedFindings, int toolCalls, int turns,
            List<Evidence> evidence, List<Exemption> exemptions, Outcome outcome,
            List<String> attempts) {
        this(unitKey, file, line, findings, abandoned, rejectedFindings, toolCalls, turns, evidence,
                exemptions, List.of(), outcome, attempts);
    }

    public boolean concluded() {
        return !findings.isEmpty();
    }

    public boolean exempted() {
        return findings.isEmpty() && !exemptions.isEmpty();
    }

    public String summary() {
        return unitKey + " — " + outcome + ", " + findings.size() + " finding(s), "
                + exemptions.size() + " exemption(s), "
                + abandoned.size() + " abandoned, " + rejectedFindings + " rejected, "
                + toolCalls + " tool call(s), " + turns + " turn(s)";
    }

    public record Abandoned(String questionId, String question, String reason) {
    }

    /**
     * A claim that a clause allows the change, with the clause wording and the code that satisfies
     * it. The clause id and quote are kept so the plan can show why a rule was not followed up.
     */
    public record Exemption(String questionId, String clauseId, String quote, String reason,
            List<String> evidenceIds, int line) {

        public Exemption {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    /**
     * How one question ended. OPEN only appears on questions still queued when the loop stopped;
     * the runner rewrites those to UNANSWERED before returning.
     */
    public record QuestionState(String id, String text, Status status, String reason,
            List<String> evidenceIds, int line, int depth) {

        public QuestionState {
            reason = reason == null ? "" : reason;
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }

        public enum Status {
            OPEN,
            CONFIRMED,
            REJECTED,
            ABANDONED,
            UNANSWERED,
            EXEMPTED
        }
    }

    /**
     * Why the loop stopped. Only CONCLUDED means every question it started with was answered; a
     * unit that never managed to phrase one is NOTHING_TO_ASK, which is a fact worth reporting.
     */
    public enum Outcome {
        CONCLUDED,
        EXEMPTED,
        NOTHING_TO_ASK,
        TOOL_BUDGET_EXHAUSTED,
        TURN_BUDGET_EXHAUSTED,
        MAX_FINDINGS_REACHED,
        STALLED,
        MODEL_FAILED
    }
}
