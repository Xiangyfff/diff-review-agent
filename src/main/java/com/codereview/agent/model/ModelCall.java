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
}
