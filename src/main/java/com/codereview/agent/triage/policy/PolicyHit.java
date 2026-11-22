package com.codereview.agent.triage.policy;

/** A policy chunk that scored above the cut, with the score kept so the plan can explain itself. */
public record PolicyHit(PolicyChunk chunk, double score) {

    public String describe() {
        return "政策摘录（不是代码证据）：" + chunk.cite() + "：" + chunk.excerpt(220);
    }
}
