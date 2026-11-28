package com.codereview.agent.investigate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The investigate loop: a queue of open questions, one model decision per turn, tools in the
 * middle, and a hard stop on both tool calls and turns.
 *
 * <p>The loop itself contains no prompt text and no notion of a language model beyond
 * {@link InvestigationModel}. That is what lets the whole stage be tested offline against a
 * scripted model and against a real repository.
 */
public final class InvestigationRunner {

    private static final int FRUITLESS_CALLS_BEFORE_STALL = 3;

    /** A question is given at most this many tool calls before it must conclude or be dropped. */
    private static final int PER_QUESTION_TOOL_CAP = 3;

    /** How deep a chain of "and what about…" questions may go. */
    private static final int MAX_QUESTION_DEPTH = 2;

    /** The whole unit is bounded, so a model that keeps asking cannot run forever. */
    private static final int MAX_QUESTIONS = 12;

    private final InvestigationModel model;
    private final Toolbox toolbox;
    private final InvestigateConfig config;
    private final java.util.Map<String, String> policyTexts;
    private final java.util.Map<String, com.codereview.agent.triage.policy.PolicyChunk> policyClauses;
    private final FindingVerifier verifier = new FindingVerifier();

    public InvestigationRunner(InvestigationModel model, Toolbox toolbox, InvestigateConfig config) {
        this(model, toolbox, config, java.util.Map.of());
    }

    /**
     * @param policyTexts clause id to clause text, for the check that a conclusion citing a policy
     *                    clause actually quotes it. Empty when the repository has no policies.
     */
    public InvestigationRunner(InvestigationModel model, Toolbox toolbox, InvestigateConfig config,
                               java.util.Map<String, String> policyTexts) {
        this(model, toolbox, config, policyTexts, java.util.Map.of());
    }

    /**
     * @param policyClauses clause id to clause, so an "exempt" turn can check that the clause is an
     *                      allowance clause, that it covers the unit's file, and that the quote is
     *                      really from it. Empty when the repository has no policies.
     */
    public InvestigationRunner(InvestigationModel model, Toolbox toolbox, InvestigateConfig config,
                               java.util.Map<String, String> policyTexts,
                               java.util.Map<String, com.codereview.agent.triage.policy.PolicyChunk> policyClauses) {
        this.model = model;
        this.toolbox = toolbox;
        this.config = config;
        this.policyTexts = policyTexts == null ? java.util.Map.of() : java.util.Map.copyOf(policyTexts);
        this.policyClauses = policyClauses == null
                ? java.util.Map.of()
                : java.util.Map.copyOf(policyClauses);
    }

    public InvestigationResult investigate(InvestigationTask task) {
        EvidenceStore store = new EvidenceStore();
        List<Question> open = new ArrayList<>();
        List<InvestigationResult.QuestionState> resolved = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        List<InvestigationResult.Exemption> exemptions = new ArrayList<>();
        List<InvestigationResult.Abandoned> abandoned = new ArrayList<>();
        Map<String, Integer> rejections = new HashMap<>();
        List<String> attempts = new ArrayList<>();
        int toolCalls = 0;
        int turns = 0;
        int rejected = 0;
        int fruitless = 0;
        int nextId = 1;
        String lastCall = null;
        String lastRejection = "";
        boolean primed = false;
        boolean opening = task.questions().isEmpty();
        boolean anyQuestion = false;
        InvestigationResult.Outcome outcome = InvestigationResult.Outcome.CONCLUDED;

        for (String text : task.questions()) {
            open.add(new Question("q" + nextId++, text));
            anyQuestion = true;
        }
        if (opening && config.maxToolCalls() > 0) {
            // The runner reads the change before asking what to look for. A model that has seen
            // nothing tends to raise questions about code it imagined; one that has the unit in
            // front of it raises questions about this code. The read is also in the evidence store,
            // so whatever gets concluded afterwards still has to cite it.
            toolCalls++;
            primed = true;
            attempts.add("primed " + runTool(primingCall(task), store));
        }

        while (true) {
            if (open.isEmpty() && !opening) {
                break;
            }
            if (turns >= config.maxTurns()) {
                outcome = InvestigationResult.Outcome.TURN_BUDGET_EXHAUSTED;
                break;
            }
            if (findings.size() >= config.maxFindings()) {
                outcome = InvestigationResult.Outcome.MAX_FINDINGS_REACHED;
                break;
            }
            turns++;
            Question current = current(open);
            InvestigationState state = new InvestigationState(task.unitKey(), task.file(), task.line(),
                    task.before(), task.after(), task.changes(), List.copyOf(open), store.all(),
                    toolCalls, config.maxToolCalls() - toolCalls, lastRejection, attempts, task.facts(),
                    opening, List.copyOf(resolved));

            ModelTurn turn;
            try {
                turn = model.next(state);
            } catch (RuntimeException e) {
                outcome = InvestigationResult.Outcome.MODEL_FAILED;
                break;
            }
            if (turn == null) {
                outcome = InvestigationResult.Outcome.MODEL_FAILED;
                break;
            }
            boolean wasOpening = opening;
            opening = false;

            if (turn instanceof ModelTurn.Raise raise) {
                int limit = wasOpening ? 1 + 2 : 1;
                List<Question> accepted = new ArrayList<>();
                String problem = null;
                for (ModelTurn.Seed seed : raise.questions()) {
                    if (accepted.size() >= limit) {
                        problem = wasOpening
                                ? "raise at least one and at most three questions"
                                : "raise at most one question at a time";
                        break;
                    }
                    problem = seedProblem(seed, task, open, resolved, nextId,
                            wasOpening ? 0 : (current == null ? 0 : current.depth() + 1), toolCallsLeft(toolCalls));
                    if (problem != null) {
                        break;
                    }
                    Question raised = new Question("q" + nextId++, seed.text(), seed.priority(),
                            wasOpening ? 0 : (current == null ? 0 : current.depth() + 1), 0);
                    accepted.add(raised);
                }
                if (!accepted.isEmpty()) {
                    open.addAll(accepted);
                    anyQuestion = true;
                    lastRejection = "";
                } else {
                    rejected++;
                    lastRejection = problem == null ? "no question was raised" : problem;
                }
                if (wasOpening && accepted.isEmpty()) {
                    outcome = InvestigationResult.Outcome.NOTHING_TO_ASK;
                    break;
                }
                continue;
            }

            if (current == null) {
                rejected++;
                lastRejection = "no question is open";
                continue;
            }
            String answered = turnQuestion(turn);
            if (!current.id().equals(answered)) {
                // Scheduling belongs to the runner: the model may say which question it is
                // answering, but it cannot pick a later one while an earlier one is open.
                rejected++;
                lastRejection = current.id() + " is the question being asked now; answer it before "
                        + (answered == null ? "anything else" : answered);
                continue;
            }

            if (turn instanceof ModelTurn.CallTool call) {
                if (current.toolsUsed() >= PER_QUESTION_TOOL_CAP) {
                    rejected++;
                    lastRejection = current.id() + " has used its " + PER_QUESTION_TOOL_CAP
                            + " tool calls; conclude, reject or abandon it";
                    continue;
                }
                if (toolCalls >= config.maxToolCalls()) {
                    outcome = InvestigationResult.Outcome.TOOL_BUDGET_EXHAUSTED;
                    break;
                }
                toolCalls++;
                replace(open, current.withToolUse());
                String signature = signatureOf(call.call());
                // Reading the same place twice tells the model nothing it did not already have, so it
                // counts as no progress. Without this, a model that lost track of what it had read
                // spent the whole budget re-reading one file.
                boolean repeated = signature.equals(lastCall);
                lastCall = signature;
                String attempt = runTool(call.call(), store);
                attempts.add(attempt);
                boolean produced = !attempt.contains("-> no excerpt") && !attempt.contains("unknown tool")
                        && !repeated;
                fruitless = produced ? 0 : fruitless + 1;
                if (fruitless >= FRUITLESS_CALLS_BEFORE_STALL) {
                    outcome = InvestigationResult.Outcome.STALLED;
                    break;
                }
            } else if (turn instanceof ModelTurn.Reject reject) {
                Optional<String> problem = rejectProblem(reject, store);
                if (problem.isEmpty()) {
                    close(open, current, resolved, new InvestigationResult.QuestionState(
                            current.id(), current.text(),
                            InvestigationResult.QuestionState.Status.REJECTED, reject.reason(),
                            reject.evidenceIds(), 0, current.depth()));
                    lastRejection = "";
                } else {
                    rejected++;
                    lastRejection = problem.get();
                }
                fruitless = 0;
            } else if (turn instanceof ModelTurn.Abandon abandon) {
                // "Nothing to see here" before looking at anything is not a finding, it is a model
                // taking the cheap way out, and a small local one does it every time. Reading the
                // changed lines is one call; making it the runner's decision rather than a request
                // in the prompt is what keeps the guarantee mechanical.
                if (!primed && toolCalls == 0 && attempts.isEmpty() && config.maxToolCalls() > 0) {
                    primed = true;
                    toolCalls++;
                    attempts.add("primed " + runTool(primingCall(task), store));
                    continue;
                }
                close(open, current, resolved, new InvestigationResult.QuestionState(
                        current.id(), current.text(),
                        InvestigationResult.QuestionState.Status.ABANDONED, abandon.reason(),
                        List.of(), 0, current.depth()));
                abandoned.add(new InvestigationResult.Abandoned(current.id(), current.text(),
                        abandon.reason()));
                fruitless = 0;
            } else if (turn instanceof ModelTurn.Conclude conclude) {
                Finding finding = new Finding(task.unitKey(), task.file(), conclude.line(),
                        conclude.statement(), conclude.evidenceIds(), conclude.demand());
                FindingVerifier.Validation validation = verifier.verify(finding, store,
                        task.line(), unitEndLine(task), task.before() + "\n" + task.after(),
                        policyTexts);
                if (validation.ok()) {
                    close(open, current, resolved, new InvestigationResult.QuestionState(
                            current.id(), current.text(),
                            InvestigationResult.QuestionState.Status.CONFIRMED,
                            conclude.statement(), conclude.evidenceIds(), conclude.line(),
                            current.depth()));
                    findings.add(finding);
                    lastRejection = "";
                } else {
                    // A rejected conclusion must not consume the question: the rejection is the
                    // reason to go and read more, not a way to close the question. The reason is
                    // handed back so the next turn can fix what the check complained about.
                    rejected++;
                    lastRejection = validation.reason();
                    int rejectedSoFar = rejections.merge(current.id(), 1, Integer::sum);
                    if (rejectedSoFar >= MAX_REJECTIONS_PER_QUESTION) {
                        close(open, current, resolved, new InvestigationResult.QuestionState(
                                current.id(), current.text(),
                                InvestigationResult.QuestionState.Status.ABANDONED,
                                "结论反复没有通过证据校验：" + validation.reason(), List.of(), 0,
                                current.depth()));
                        abandoned.add(new InvestigationResult.Abandoned(current.id(), current.text(),
                                "结论反复没有通过证据校验：" + validation.reason()));
                    }
                }
                fruitless = 0;
            } else if (turn instanceof ModelTurn.Exempt exempt) {
                Optional<String> problem = exemptionProblem(exempt, store, task.file());
                if (problem.isEmpty()) {
                    close(open, current, resolved, new InvestigationResult.QuestionState(
                            current.id(), current.text(),
                            InvestigationResult.QuestionState.Status.EXEMPTED,
                            exempt.reason(), exempt.evidenceIds(), exempt.line(), current.depth()));
                    exemptions.add(new InvestigationResult.Exemption(current.id(), exempt.clause(),
                            exempt.quote(), exempt.reason(), exempt.evidenceIds(), exempt.line()));
                    lastRejection = "";
                } else {
                    // Same rule as a rejected conclusion: the question stays open, the reason goes
                    // back into the prompt, and two failures close it with the reason recorded.
                    rejected++;
                    lastRejection = problem.get();
                    int rejectedSoFar = rejections.merge(current.id(), 1, Integer::sum);
                    if (rejectedSoFar >= MAX_REJECTIONS_PER_QUESTION) {
                        close(open, current, resolved, new InvestigationResult.QuestionState(
                                current.id(), current.text(),
                                InvestigationResult.QuestionState.Status.ABANDONED,
                                "豁免反复没有通过校验：" + problem.get(), List.of(), 0,
                                current.depth()));
                        abandoned.add(new InvestigationResult.Abandoned(current.id(), current.text(),
                                "豁免反复没有通过校验：" + problem.get()));
                    }
                }
                fruitless = 0;
            }
            if (findings.size() >= config.maxFindings() && !open.isEmpty()) {
                outcome = InvestigationResult.Outcome.MAX_FINDINGS_REACHED;
                break;
            }
        }

        for (Question question : open) {
            resolved.add(new InvestigationResult.QuestionState(question.id(), question.text(),
                    InvestigationResult.QuestionState.Status.UNANSWERED, "", List.of(), 0,
                    question.depth()));
        }

        if (outcome == InvestigationResult.Outcome.CONCLUDED) {
            if (findings.isEmpty() && !exemptions.isEmpty()) {
                outcome = InvestigationResult.Outcome.EXEMPTED;
            } else if (findings.isEmpty() && !anyQuestion) {
                outcome = InvestigationResult.Outcome.NOTHING_TO_ASK;
            }
        }

        return new InvestigationResult(task.unitKey(), task.file(), task.line(), findings, abandoned,
                rejected, toolCalls, turns, store.all(), List.copyOf(exemptions),
                List.copyOf(resolved), outcome, List.copyOf(attempts));
    }

    private int toolCallsLeft(int used) {
        return config.maxToolCalls() - used;
    }

    /**
     * Why a proposed question cannot enter the queue. Text has to be short, the target has to exist
     * in the unit the runner showed, and the chain of follow-up questions is bounded.
     */
    private String seedProblem(ModelTurn.Seed seed, InvestigationTask task, List<Question> open,
            List<InvestigationResult.QuestionState> resolved, int nextId, int depth, int callsLeft) {
        if (seed.text().isBlank()) {
            return "a question needs text";
        }
        if (seed.text().length() > 300) {
            return "a question must stay under 300 characters";
        }
        if (seed.target().isBlank() || seed.target().length() > 120) {
            return "a question needs a target symbol";
        }
        boolean anchored = task.before().contains(seed.target())
                || task.after().contains(seed.target())
                || task.unitKey().contains(seed.target())
                || task.changes().stream().anyMatch(change -> change.contains(seed.target()));
        if (!anchored) {
            return "the target " + seed.target() + " does not appear in this unit";
        }
        boolean duplicate = open.stream().anyMatch(question -> question.text().equals(seed.text()))
                || resolved.stream().anyMatch(question -> question.text().equals(seed.text()));
        if (duplicate) {
            return "that question has already been asked";
        }
        if (depth > MAX_QUESTION_DEPTH) {
            return "follow-up questions are already " + MAX_QUESTION_DEPTH + " deep";
        }
        if (open.size() + resolved.size() >= MAX_QUESTIONS) {
            return "this unit already has " + MAX_QUESTIONS + " questions";
        }
        if (callsLeft < 2) {
            return "not enough tool budget left to investigate another question";
        }
        return null;
    }

    private Optional<String> rejectProblem(ModelTurn.Reject reject, EvidenceStore store) {
        if (reject.reason() == null || reject.reason().isBlank()) {
            return Optional.of("a rejection needs a reason");
        }
        if (reject.evidenceIds().isEmpty()) {
            return Optional.of("a rejection must cite the code it read");
        }
        for (String id : reject.evidenceIds()) {
            if (store.find(id).isEmpty()) {
                return Optional.of("evidence " + id + " does not exist");
            }
        }
        return Optional.empty();
    }

    private static Question current(List<Question> open) {
        return open.stream().min(java.util.Comparator
                        .comparingInt(Question::priority)
                        .thenComparingInt(question -> idNumber(question.id())))
                .orElse(null);
    }

    private static int idNumber(String id) {
        try {
            return Integer.parseInt(id.substring(1));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void replace(List<Question> open, Question updated) {
        for (int index = 0; index < open.size(); index++) {
            if (open.get(index).id().equals(updated.id())) {
                open.set(index, updated);
                return;
            }
        }
    }

    private static void close(List<Question> open, Question question,
            List<InvestigationResult.QuestionState> resolved,
            InvestigationResult.QuestionState state) {
        open.remove(question);
        resolved.add(state);
    }

    private static String turnQuestion(ModelTurn turn) {
        return switch (turn) {
            case ModelTurn.CallTool call -> call.questionId();
            case ModelTurn.Abandon abandon -> abandon.questionId();
            case ModelTurn.Conclude conclude -> conclude.questionId();
            case ModelTurn.Exempt exempt -> exempt.questionId();
            case ModelTurn.Reject reject -> reject.questionId();
            case ModelTurn.Raise raise -> null;
        };
    }

    /**
     * @return whether the call produced at least one excerpt, which is what "progress" means here
     */
    private static final int MAX_REJECTIONS_PER_QUESTION = 2;

    /**
     * @return a one-line record of the call for the next prompt. A call that returned nothing still
     *         has to be visible: without it the model cannot tell that it already tried this, and it
     *         retries until the budget is gone.
     */
    private String runTool(ToolCall call, EvidenceStore store) {
        String label = call.tool() + "(" + call.arguments().values() + ")";
        Optional<ReviewTool> tool = toolbox.find(call.tool());
        if (tool.isEmpty()) {
            return label + " -> unknown tool";
        }
        ToolOutcome result = tool.get().run(call);
        for (ToolOutcome.Excerpt excerpt : result.excerpts()) {
            store.add(call.tool(), excerpt.file(), excerpt.startLine(), excerpt.endLine(), excerpt.text());
        }
        if (result.excerpts().isEmpty()) {
            return label + " -> no excerpt (" + result.summary() + ")";
        }
        return label + " -> " + result.excerpts().size() + " excerpt(s)";
    }

    /**
     * Checks an "exempt" turn the way {@link FindingVerifier} checks a conclusion: the clause must
     * exist, must be an allowance clause (rules, scope and examples all present), must cover this
     * file, the quote must come from its wording, and the cited evidence must actually show the
     * line. Only then may the question close without a finding.
     */
    private Optional<String> exemptionProblem(ModelTurn.Exempt exempt, EvidenceStore store, String file) {
        if (exempt.clause() == null || exempt.clause().isBlank()) {
            return Optional.of("an exemption must name the policy clause it relies on");
        }
        com.codereview.agent.triage.policy.PolicyChunk clause = policyClauses.get(exempt.clause());
        if (clause == null) {
            return Optional.of("cited policy clause " + exempt.clause() + " does not exist");
        }
        if (!clause.canExempt()) {
            return Optional.of("clause " + exempt.clause()
                    + " is not an allowance clause with rules, scope and an example");
        }
        boolean inScope = clause.scope().stream()
                .anyMatch(pattern -> com.codereview.agent.triage.policy.PolicyExemption
                        .matches(pattern, file));
        if (!inScope) {
            return Optional.of("clause " + exempt.clause() + " does not cover " + file);
        }
        if (!FindingVerifier.quotesClause(exempt.quote(), clause.text())) {
            return Optional.of("the quote is not from clause " + exempt.clause());
        }
        if (exempt.evidenceIds().isEmpty()) {
            return Optional.of("an exemption must cite the code it read");
        }
        for (String id : exempt.evidenceIds()) {
            if (store.find(id).isEmpty()) {
                return Optional.of("evidence " + id + " does not exist");
            }
        }
        boolean covered = store.all().stream().anyMatch(evidence ->
                evidence.covers(file, exempt.line()) && exempt.evidenceIds().contains(evidence.id()));
        if (!covered) {
            return Optional.of("line " + exempt.line() + " is not covered by any cited evidence");
        }
        return Optional.empty();
    }

    private static String signatureOf(ToolCall call) {        StringBuilder text = new StringBuilder(call.tool()).append('(');
        call.arguments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> text.append(entry.getKey()).append('=')
                        .append(entry.getValue()).append(';'));
        return text.append(')').toString();
    }

    /**
     * The read the runner does itself when a model wants to give up without having read the
     * change. Same tool and same evidence store, so a conclusion drawn afterwards still has to
     * cite what the read produced.
     */
    private ToolCall primingCall(InvestigationTask task) {
        return new ToolCall("read_lines", Map.of(
                "path", task.file(),
                "start", Integer.toString(task.line()),
                "end", Integer.toString(unitEndLine(task))));
    }

    private static int unitEndLine(InvestigationTask task) {
        String text = task.after() == null ? "" : task.after();
        long lines = text.isEmpty() ? 0 : text.lines().count();
        return (int) (task.line() + Math.max(0, lines - 1));
    }
}
