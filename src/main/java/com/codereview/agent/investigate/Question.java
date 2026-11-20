package com.codereview.agent.investigate;

/**
 * One question triage handed over, carried through the loop until it is answered or abandoned.
 */
public record Question(String id, String text) {
}
