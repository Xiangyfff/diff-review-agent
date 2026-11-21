# diff-review-agent

A code review agent that reads a git diff, decides which hunks are worth investigating, and only
leaves a comment when it can point at the evidence it found.

Review bots usually fail in three ways: they comment before reading the surrounding code, they never
leave the diff to look at the caller, and nobody can say afterwards whether their comments were
right. This project is built around those three failures.

## 中文简介

代码审查智能体常见的三个毛病是：没看上下文就评论、只看改动不看调用方、评论完没人能判断对不对。这个项目围绕这三件事设计。

输入一次提交的差异，先做初审：把改动切成以函数为单位的分析单元，比较前后两版语法树，找出形状确实发生变化的地方——比如原本有一句判空、改完之后没有了，这和「代码里现在没有判空」是两件完全不同的事，现成的静态工具分不出来。再用一小组规则决定哪些单元必须查、哪些可以跳过。值得查的单元会拿到几个具体的待验证问题，留给下一段深挖逐个回答。初审只输出一份可以序列化的调查计划，不产生任何评论。

深挖阶段把每个待查单元变成一场有预算的调查：模型每一轮只能在三个动作里选一个——调用一个只读工具、放弃并说明理由、或者在引用证据的前提下下结论。工具的返回全部进入一个带编号的证据库，结论只能引用真实拿到过的编号；编号对不上、引用的行不在证据范围内、讨论的符号没在证据原文里出现过，这三种情况都会被程序挡掉。

复核阶段专门反驳：给它的任务不是「你觉得对吗」，而是「找出一个让这条结论不成立的具体事实，并且用工具读出来」。反驳同样必须引用本轮自己收集到的证据，拿不出证据就不算反驳——否则复核会靠感觉否掉真问题。

评估用变异体做标准答案：在五个文件上注入十六个改动，十一个有明确缺陷，五个是等价重构，然后量三件事——单元级召回、干净改动被误排的比例、以及最终评论有没有落在缺陷那一行附近。

当前进度：三段式全部落地，七十八个测试，全程离线可跑，也已经在真实仓库（spring-petclinic 的四个提交）上跑通过。模型层目前还是假模型，LangChain4j 适配器是下一步。技术栈是 Java 21、JGit、JavaParser、Jackson。

## How it works

```
diff ──> triage ──> investigate ──> verify ──> comments
```

**Triage** parses the patch, cuts the changes into function-sized units, compares the before and
after syntax tree of each unit, and runs a small rule catalogue over the *changes themselves* — a
null check that disappeared is a different event from one that was never there. Rules that demand
attention produce concrete questions the unit has to answer before it may be commented on. Units
that do not earn a question are dropped; units that do are scheduled under a budget.

**Investigate** takes only the scheduled units and runs a bounded loop over five read-only tools. Its
only three allowed moves are: call a tool, abandon a question with a reason, or conclude while citing
evidence ids. Tool output never enters the conversation as free text — it goes into an evidence store
and comes back as an id plus a summary, so a conclusion can point at it but cannot rewrite it.

**Verify** is a critic that must refute, not agree. A refutation has to cite evidence the critic read
itself in this round; a refutation without evidence is counted and the finding stands. Survivors are
kept as assertions, ones that cannot be proven either way are downgraded to questions, and only
strong counter-evidence retracts.

## Status

- [x] Triage — patch parsing, unit mapping, structural change detection, rule catalogue, tiering,
      question validation, name-level repo index, budgeted plan, deterministic offline model
- [x] Investigate — five read-only tools, question queue, evidence store, five mechanical checks,
      six counted outcomes
- [x] Verify — evidence-backed refutation, keep / downgrade / retract
- [x] Evaluation — 16 mutation cases, report written to `eval/latest-report.md`
- [x] Pipeline — triage → investigate → verify → comments, one command
- [x] 107 tests, no network required
- [x] LangChain4j model adapter — one class imports the SDK, everything else speaks prompts and JSON
- [x] Cheap gate for the judge tier — a small, shapeless change never reaches the model
- [ ] Real-model numbers (needs an API key; the offline numbers above come from the stand-ins)
- [ ] GitHub App that posts the comments as a review

## Design notes

- **Evidence is checked, not requested.** A finding must cite evidence ids that resolve in the
  observation store, and every file and line a comment mentions must appear in the cited evidence.
  If that check fails the finding is dropped, not softened.
- **A unit is a function.** Static analysis, coverage and call sites already speak in methods, so
  choosing a function as the unit means no signal ever needs re-mapping.
- **The budget is counted in units, not tokens.** A token budget is already spent by the time you
  know where it went, and it starves a hard unit halfway through; a unit budget is fixed up front,
  reproducible, and explainable in the plan.
- **Triage is separable.** Its output is a JSON plan, so it can be run alone, diffed between runs,
  and scored without spending a model call.
- **Evidence is folded, not deleted.** The store keeps every excerpt and the conclusion-time checks
  read the store, not the prompt. In the prompt, the newest few entries stay verbatim and older ones
  collapse to their id and location, so they remain citable and re-readable through a tool call. On a
  unit that gathers ten excerpts the evidence block drops from 2,820 to 480 characters.
- **The model layer is a thin interface.** Tests run against a deterministic offline model; the
  LangChain4j adapter is one implementation behind it.

## Evaluation

`mvn -q compile` then `java -cp "target/classes;<classpath>" com.codereview.agent.eval.EvalRunner`
replays 22 mutations through the whole pipeline and writes `eval/latest-report.md` and
`eval/latest-report.json`. Add `--real` to use the configured model instead of the stand-ins.

Real model (`deepseek-chat`, temperature 0), latest run:

| metric | value |
|---|---|
| triage unit recall | 88% (14/16) |
| triage false positive rate | 17% (1/6) |
| rule-gated recall (MUST, no model involved) | 88% (14/16) |
| rule-gated false positives (MUST on clean changes) | 17% (1/6) |
| end-to-end recall | 69% (11/16) |
| findings | 14 |
| kept / downgraded / dropped | 14 / 0 / 0 |

Offline stand-ins, same corpus:

| metric | value |
|---|---|
| triage unit recall | 94% (15/16) |
| triage false positive rate | 33% (2/6) |
| end-to-end recall | 75% (12/16) |
| kept / downgraded / dropped | 16 / 0 / 1 |

Read these carefully. The stand-in models never decline: the triage stand-in asks a question about
every structural change, and the verifier stand-in retracts as soon as it reads a null check anywhere
nearby — which is how one real defect (`resource-unclosed`) was dropped. Those rows measure wiring, not
model quality. The rule-gated rows are the part no model can influence, and they are identical in both
runs.

Two semantic cases were added next, where no shape signals the bug. Logging the password in
`AuthService.login` is caught, because the auth path is already flagged by rule; it took the model reading
the line to say what was wrong with it. Moving `validate(order)` after `repository.save(order)` was not caught at
all at first: two statements swapped places, no rule fired, and the cheap gate dropped the unit as a small
shapeless change. A reorder is never a no-op — side effects have an order — so the statement multiset is
now compared before the diff runs and a pure reorder is a mandatory shape. That cost one false positive at
triage (the clean case swapping two list additions is investigated and comes back with no finding, so it
pays model calls and produces no comment) and bought the ordering defect back.

Two of the new cases were added precisely because the first expanded run missed them, and each
exposed a real gap. A boundary bug (`> 10` becoming `>= 10`) changed no shape the detector knew, so
`OPERATOR-CHANGED` now compares statements with their operators blanked out and fires when only the
operator differs. And the verification gate rejected correct conclusions that named removed code
(`@Transactional`, `quantity > 10`): those strings cannot appear in the evidence, because the change
took them away. Symbols are now also accepted from the unit's own before/after text — the diff is
trusted input, the model is not — so invented names still die while conclusions about removals live.

The real model is the opposite trade: it declines judge-tier units it finds unremarkable, so recall
depends on how much the program decides before the model is asked. Two families are now decided by the
program rather than by a judgement: control-flow shapes (a flipped condition, a lost early exit) and
deletions with nothing added in their place. That moved recall from 64% to 82% with the false-positive
rate still at zero, because clean refactors do not produce those shapes. Investigation used to lose the units it was given: two of them were
scheduled with an empty question list (a rule that gates on a fact carries no shape evidence, and the
fallback skipped those hits), and the loop could not tell that a tool call had already been made and
returned nothing, so it retried the same call until the budget ran out. Both are fixed; what is left
is two units whose conclusions pointed at lines the cited evidence does not cover.

One run of sixteen cases is one sample, not a measurement of the model — the same code re-run shifted
end-to-end recall by nine points. Treat the rule-gated rows as the stable part and the rest as a trend.

The cheap gate is what moved the false-positive rate from 100% to 60% without touching recall: a unit
whose changed parts are all shapeless leftovers and whose diff is four lines or fewer is skipped
without a model call. It is deliberately conservative — any rule hit, mandatory or advisory, wins over
it, because a one-line condition flip is exactly the small change that must not be waved through.

To measure against a real model, set `CODEREVIEW_LLM_API_KEY` (and optionally
`CODEREVIEW_LLM_BASE_URL`, `CODEREVIEW_LLM_MODEL`) and run `ReviewPipeline` instead of the offline
stand-ins; the harness itself is unchanged.

## Build

```bash
mvn test
```

Requires JDK 21. No API keys are needed for the test suite.

## License

MIT
