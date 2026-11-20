package com.codereview.agent.model;

import com.codereview.agent.investigate.InvestigationModel;
import com.codereview.agent.investigate.Toolbox;
import com.codereview.agent.review.ModelFactory;
import com.codereview.agent.triage.model.TriageModel;
import com.codereview.agent.verify.Verifier;

/**
 * Builds the three stage adapters from one set of settings.
 *
 * <p>One place, one chat: the stages differ in what they are asked, not in how they reach the model,
 * so a provider change is a change to {@link Chat} only.
 */
public final class ReviewModels implements ModelFactory {

    private final Chat chat;
    private final String description;

    public ReviewModels(Chat chat, String description) {
        this.chat = chat;
        this.description = description;
    }

    public static ReviewModels fromEnvironment() {
        ModelSettings settings = ModelSettings.fromEnvironment();
        return new ReviewModels(Chat.openAiCompatible(settings), settings.describe());
    }

    public TriageModel triage() {
        return new LangChain4jTriageModel(chat);
    }

    public InvestigationModel investigation(Toolbox toolbox) {
        return new LangChain4jInvestigationModel(chat, toolbox);
    }

    public Verifier verifier() {
        return new LangChain4jVerifier(chat);
    }

    /** What the report should say about the model, never including the key. */
    @Override
    public String describe() {
        return description;
    }
}
