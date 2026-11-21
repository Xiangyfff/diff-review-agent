package com.codereview.agent.model;

/**
 * JSON Schemas for the four structured answers the pipeline asks for.
 *
 * <p>They are passed to the provider as a forced tool call, so the reply arrives as tool arguments
 * that already conform to the shape. The prompts still describe the meaning; the schema carries the
 * structure, which is the part a prompt cannot guarantee.
 */
final class Schemas {

    static final String TRIAGE_ASSESSMENT = """
            {"type":"object","properties":{
              "questions":{"type":"array","items":{"type":"object","properties":{
                 "target":{"type":"string"},"text":{"type":"string"}},
                 "required":["target","text"]}},
              "worthInvestigating":{"type":"boolean"},
              "abstainReason":{"type":"string"},
              "rationale":{"type":"string"}},
             "required":["questions","worthInvestigating","abstainReason","rationale"]}
            """;

    static final String TRIAGE_SUMMARY = """
            {"type":"object","properties":{
              "intent":{"type":"string"},
              "crossFileConcerns":{"type":"array","items":{"type":"string"}}},
             "required":["intent","crossFileConcerns"]}
            """;

    static final String INVESTIGATION_TURN = """
            {"type":"object","properties":{
              "action":{"type":"string","enum":["tool","abandon","conclude"]},
              "question":{"type":"string"},
              "tool":{"type":"string"},
              "arguments":{"type":"object","additionalProperties":{"type":"string"}},
              "reason":{"type":"string"},
              "statement":{"type":"string"},
              "evidence":{"type":"array","items":{"type":"string"}},
              "line":{"type":"integer"},
              "demand":{"type":"string","enum":["fix","confirm"]}},
             "required":["action","question"]}
            """;

    static final String VERDICT = """
            {"type":"object","properties":{
              "action":{"type":"string","enum":["tool","keep","retract","downgrade"]},
              "tool":{"type":"string"},
              "arguments":{"type":"object","additionalProperties":{"type":"string"}},
              "reason":{"type":"string"},
              "counterEvidence":{"type":"array","items":{"type":"string"}}},
             "required":["action","reason"]}
            """;

    private Schemas() {
    }
}
