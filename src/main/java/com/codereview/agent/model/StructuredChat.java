package com.codereview.agent.model;

import java.util.logging.Logger;

/**
 * A chat that asks the provider for schema-constrained answers, and quietly stops trying when the
 * provider cannot do it.
 *
 * <p>Forced tool calls are the only reliable constraint on an OpenAI-compatible endpoint, but not
 * every endpoint honours them: a local Ollama model may accept the tool definitions and answer in
 * prose anyway, and some gateways reject the request outright. Discovering that once per run is
 * cheaper than discovering it per call, so the first refusal switches the whole session to asking
 * for the same schema in words — the repair loop in {@link ModelCall} already covers the syntax
 * mistakes that follow.
 *
 * <p>Only failures that read as "this model will not call tools" trigger the switch. Transport
 * errors, timeouts and bad responses keep their ordinary failure so the stage can count them.
 */
final class StructuredChat implements Chat {

    private static final Logger LOG = Logger.getLogger(StructuredChat.class.getName());

    private static final String MARKER = "did not call";

    private final Chat delegate;

    /**
     * Volatile because units are investigated on virtual threads in parallel; a redundant first
     * failure costs one extra call, while a lost update could flip the flag back on.
     */
    private volatile boolean toolCallsAccepted = true;

    StructuredChat(Chat delegate) {
        this.delegate = delegate;
    }

    @Override
    public String complete(String system, String user) {
        return delegate.complete(system, user);
    }

    @Override
    public String completeStructured(String system, String user, String toolName,
                                     String toolDescription, String parametersJson) {
        if (toolCallsAccepted) {
            try {
                return delegate.completeStructured(system, user, toolName, toolDescription,
                        parametersJson);
            } catch (RuntimeException e) {
                if (!readsAsNoToolCalls(e)) {
                    throw e;
                }
                toolCallsAccepted = false;
                LOG.info(() -> "this endpoint does not honour forced tool calls (" + e.getMessage()
                        + "); asking for the same schema in prose from now on");
            }
        }
        return inProse(system, user, toolName, parametersJson);
    }

    /**
     * Same schema, no tool definitions: the shape is stated in the prompt and checked afterwards.
     */
    private String inProse(String system, String user, String toolName, String parametersJson) {
        String instruction = String.join("\n",
                user,
                "",
                "Answer with one JSON object and nothing else: no explanation, no markdown fence.",
                "It must be the arguments of a call to " + toolName + " and follow this JSON Schema:",
                parametersJson,
                "",
                "Write the arguments themselves as the top-level object. Do not wrap them in"
                        + " {\"name\": ..., \"arguments\": ...}, and do not mention " + toolName
                        + " in the answer.");
        return delegate.complete(system, instruction);
    }

    private static boolean readsAsNoToolCalls(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // Our own wording when the answer came back as prose, and the wording providers use when
        // they refuse the tool definitions at all.
        return message.contains(MARKER) || message.toLowerCase().contains("tool");
    }
}
