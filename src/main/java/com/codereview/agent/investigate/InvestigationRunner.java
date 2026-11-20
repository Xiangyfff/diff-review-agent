package com.codereview.agent.investigate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
                    toolCalls, config.maxToolCalls() - toolCalls);

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
                boolean produced = runTool(call.call(), store);
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
                Optional<Question> question = remove(open, conclude.questionId());
                if (question.isEmpty()) {
                    rejected++;
                    continue;
                }
                Finding finding = new Finding(task.unitKey(), task.file(), conclude.line(),
                        conclude.statement(), conclude.evidenceIds());
                FindingVerifier.Validation validation = verifier.verify(finding, store);
                if (validation.ok()) {
                    findings.add(finding);
                } else {
                    rejected++;
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
    private boolean runTool(ToolCall call, EvidenceStore store) {
        Optional<ReviewTool> tool = toolbox.find(call.tool());
        if (tool.isEmpty()) {
            return false;
        }
        ToolOutcome result = tool.get().run(call);
        for (ToolOutcome.Excerpt excerpt : result.excerpts()) {
            store.add(call.tool(), excerpt.file(), excerpt.startLine(), excerpt.endLine(), excerpt.text());
        }
        return !result.excerpts().isEmpty();
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
