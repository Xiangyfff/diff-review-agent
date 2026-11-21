package com.codereview.agent.investigate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    private final InvestigationModel model;
    private final Toolbox toolbox;
    private final InvestigateConfig config;
    private final FindingVerifier verifier = new FindingVerifier();

    public InvestigationRunner(InvestigationModel model, Toolbox toolbox, InvestigateConfig config) {
        this.model = model;
        this.toolbox = toolbox;
        this.config = config;
    }

    public InvestigationResult investigate(InvestigationTask task) {
        EvidenceStore store = new EvidenceStore();
        Deque<Question> open = new ArrayDeque<>();
        for (int index = 0; index < task.questions().size(); index++) {
            open.add(new Question("q" + (index + 1), task.questions().get(index)));
        }
        List<Finding> findings = new ArrayList<>();
        List<InvestigationResult.Abandoned> abandoned = new ArrayList<>();
        int toolCalls = 0;
        int turns = 0;
        int rejected = 0;
        int fruitless = 0;
        String lastCall = null;
        String lastRejection = "";
        List<String> attempts = new ArrayList<>();
        Map<String, Integer> rejections = new HashMap<>();
        InvestigationResult.Outcome outcome = InvestigationResult.Outcome.CONCLUDED;

        while (!open.isEmpty()) {
            if (turns >= config.maxTurns()) {
                outcome = InvestigationResult.Outcome.TURN_BUDGET_EXHAUSTED;
                break;
            }
            if (findings.size() >= config.maxFindings()) {
                outcome = InvestigationResult.Outcome.MAX_FINDINGS_REACHED;
                break;
            }
            turns++;
            InvestigationState state = new InvestigationState(task.unitKey(), task.file(), task.line(),
                    task.before(), task.after(), task.changes(), List.copyOf(open), store.all(),
                    toolCalls, config.maxToolCalls() - toolCalls, lastRejection, attempts);

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

            if (turn instanceof ModelTurn.CallTool call) {
                if (toolCalls >= config.maxToolCalls()) {
                    outcome = InvestigationResult.Outcome.TOOL_BUDGET_EXHAUSTED;
                    break;
                }
                toolCalls++;
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
            } else if (turn instanceof ModelTurn.Abandon abandon) {
                Optional<Question> question = remove(open, abandon.questionId());
                if (question.isEmpty()) {
                    fruitless++;
                } else {
                    abandoned.add(new InvestigationResult.Abandoned(
                            question.get().id(), question.get().text(), abandon.reason()));
                    fruitless = 0;
                }
            } else if (turn instanceof ModelTurn.Conclude conclude) {
                Optional<Question> question = find(open, conclude.questionId());
                if (question.isEmpty()) {
                    rejected++;
                    continue;
                }
                Finding finding = new Finding(task.unitKey(), task.file(), conclude.line(),
                        conclude.statement(), conclude.evidenceIds());
                FindingVerifier.Validation validation = verifier.verify(finding, store,
                        task.line(), unitEndLine(task));
                if (validation.ok()) {
                    remove(open, conclude.questionId());
                    findings.add(finding);
                    lastRejection = "";
                } else {
                    // A rejected conclusion must not consume the question: the rejection is the
                    // reason to go and read more, not a way to close the question. The reason is
                    // handed back so the next turn can fix what the check complained about.
                    rejected++;
                    lastRejection = validation.reason();
                    int rejectedSoFar = rejections.merge(question.get().id(), 1, Integer::sum);
                    if (rejectedSoFar >= MAX_REJECTIONS_PER_QUESTION) {
                        remove(open, conclude.questionId());
                        abandoned.add(new InvestigationResult.Abandoned(question.get().id(),
                                question.get().text(),
                                "结论反复没有通过证据校验：" + validation.reason()));
                    }
                }
                fruitless = 0;
            }
            if (findings.size() >= config.maxFindings() && !open.isEmpty()) {
                outcome = InvestigationResult.Outcome.MAX_FINDINGS_REACHED;
                break;
            }
        }

        return new InvestigationResult(task.unitKey(), task.file(), task.line(), findings, abandoned,
                rejected, toolCalls, turns, store.all(), outcome);
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

    private static String signatureOf(ToolCall call) {
        StringBuilder text = new StringBuilder(call.tool()).append('(');
        call.arguments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> text.append(entry.getKey()).append('=')
                        .append(entry.getValue()).append(';'));
        return text.append(')').toString();
    }

    private static int unitEndLine(InvestigationTask task) {
        String text = task.after() == null ? "" : task.after();
        long lines = text.isEmpty() ? 0 : text.lines().count();
        return (int) (task.line() + Math.max(0, lines - 1));
    }

    private static Optional<Question> find(Deque<Question> open, String id) {
        if (id == null) {
            return Optional.empty();
        }
        return open.stream().filter(question -> question.id().equals(id)).findFirst();
    }

    private static Optional<Question> remove(Deque<Question> open, String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (Question question : open) {
            if (question.id().equals(id)) {
                open.remove(question);
                return Optional.of(question);
            }
        }
        return Optional.empty();
    }
}
