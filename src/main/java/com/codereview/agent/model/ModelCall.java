package com.codereview.agent.model;

/**
 * One question to the model with one repair attempt.
 *
 * <p>Asking again after a malformed reply is worth it: the second failure is nearly always a real
 * problem with the prompt rather than bad luck, and the caller needs to see it as an exception so the
 * stage can count it instead of silently using a half-parsed answer.
 */
final class ModelCall {

    private ModelCall() {
    }

    static String ask(Chat chat, String system, String user) {
        String first = chat.complete(system, user);
        try {
            JsonReplies.object(first);
            return first;
        } catch (RuntimeException failure) {
            String repair = user + "\n\n上一次的输出不是合法的 JSON 对象（" + failure.getMessage()
                    + "）。请重新输出，只输出一个 JSON 对象，不要加解释、不要加代码块。";
            String second = chat.complete(system, repair);
            JsonReplies.object(second);
            return second;
        }
    }

    /**
     * Same repair policy, but the answer is constrained by a forced tool call rather than by asking
     * nicely for JSON. A provider that honours tool choice cannot answer in the wrong shape, which
     * removes the whole class of "the model added a sentence before the object" failures.
     */
    static String ask(Chat chat, String system, String user, String toolName,
                      String toolDescription, String parametersJson) {
        String first = chat.completeStructured(system, user, toolName, toolDescription, parametersJson);
        try {
            JsonReplies.object(first);
            return first;
        } catch (RuntimeException failure) {
            String repair = user + "\n\n上一次调用 " + toolName + " 时给出的参数不是合法的 JSON 对象（"
                    + failure.getMessage() + "）。请重新调用同一个工具，只传参数。";
            String second = chat.completeStructured(system, repair, toolName, toolDescription, parametersJson);
            JsonReplies.object(second);
            return second;
        }
    }
}
