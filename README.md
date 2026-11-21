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
- [x] Evaluation — 22 mutation cases, report written to `eval/latest-report.md`
- [x] Pipeline — triage → investigate → verify → comments, one command
- [x] 146 tests, no network required
- [x] LangChain4j model adapter — one class imports the SDK, everything else speaks prompts and JSON
- [x] Cheap gate for the judge tier — a small, shapeless change never reaches the model
- [x] Real-model numbers (`deepseek-chat`, 22 cases; see the Evaluation table)
- [x] Ablation across the three depths, written to `eval/latest-ablation.md`
- [x] Token ledger — every run reports calls and tokens instead of assuming the cost
- [x] Demo page — browse commits, review an edit made in the page, pick a depth, pull a screened file back in
- [x] Pull-request publishing — dry run by default, inline comments filtered to the diff
- [x] Sampling — a deterministic slice of the units the gate skipped, reported per run and in the demo
- [x] Static analysis as a second fact source, limited to warnings on the added lines
- [ ] Split a unit that is too large at method boundaries

## Knowing when "nobody calls it" is not the answer

A call graph that comes back empty is ambiguous: either nobody uses the method, or the callers live
outside this checkout. The graph alone cannot tell the two apart, so the model used to abandon those
questions and leave only a fallback. `public_surface` answers the missing half — a `public` method on
a `public` type is reachable from outside, so an empty caller list says nothing about external
callers and the change has to be raised as a confirmation instead of dropped.

That one check moved evidence-backed recall from 50% to 65% on the same corpus, with no change to
the triage stage and no extra model call. It is the cheapest improvement in the project: one
syntactic look at the declaration's modifiers, and the loop stops treating "no caller" as "no
problem".

## Calling the code, not the name

`find_references` answers "which files mention this name", which is cheap and sometimes wrong:
two classes with a `save` method, an overload, an interface with one implementation. `find_callers`
resolves types instead and answers "which call sites actually reach this declaration". It is slower,
so it exists only for the question that needs the accuracy.

The difference is measurable without a model, so it is measured. On spring-petclinic, over the first
87 methods of `src/main/java`:

| | count |
| --- | --- |
| file mentions reported by the name index | 267 |
| call sites resolved by the call graph | 181 |
| mentions that belong to a method nobody calls | 99 |

The `name-only` column is where the two disagree in the dangerous direction. `NamedEntity.toString`
is mentioned in eight files and called by none of them — every one of those calls lands on a
different `toString`. A reviewer told "eight callers" would go looking for eight places to change.

Reproduce with `com.codereview.agent.eval.CallerComparison <repository> <output.md>`; it needs no
API key. The table is written to `eval/latest-callers.md`.

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
- **Each stage hands the next one its findings, not just its conclusions.** Triage passes its
  name-level facts into the investigation prompt (marked as leads, never as verdicts), and
  investigation passes its own tool-call log into the critic's prompt, including the calls that
  found nothing, so the critic does not spend its four calls repeating them.
- **A comment has to ask for something.** Every conclusion carries a demand: either `fix`
  (something concrete is wrong here) or `confirm` (the author has to check something). A sentence
  that only restates the diff is not a conclusion, and the verifier rejects a conclusion with no
  demand at all; the prompt tells the model to abandon the question instead of filling the space.
- **The answer shape is a schema, not a request.** The four replies the pipeline needs (triage
  assessment, change summary, investigation turn, critic verdict) are forced through a tool call
  whose JSON Schema is fixed in code, so a provider that honours tool choice cannot answer in the
  wrong shape. DeepSeek rejects `json_schema` response formats, and `json_object` only promises
  valid JSON, not the fields; a required tool call constrains both. The structured call also uses a
  separate client instance, because asking for `json_object` and a tool call at once is rejected.
- **The units the gate skipped are sampled, not trusted.** A review tool that says "nothing here"
  cannot be checked by reading its comments, because the interesting cases are the ones it never
  wrote. Setting a sample rate (5% is a reasonable default, 0 is off) sends a deterministic slice of
  the skipped units through the same investigation and the same evidence gate, and the run reports
  how many of them produced an evidence-backed conclusion. The slice is chosen from a hash of the
  unit key, so re-running the same commit samples the same units and the estimate is comparable
  between runs. It measures "of the units we let through, how many had something to say" — not the
  true miss rate, which needs a corpus with known answers.
- **Static analysis only speaks about the new lines.** A type-level defect (a string compared with
  `==`, a caught `Throwable`, a stack trace printed to production) is invisible to structural
  comparison, so it is a second fact source for triage. It runs on the head version and keeps only
  warnings that land on the added lines: without that filter every review drowns in warnings the
  author did not cause. The string check fires only when one side is a literal, which trades recall
  for precision on purpose — flagging every `==` between two variables would report arithmetic
  comparisons on every file.
- **Running the tests is deliberately out of scope.** Executing the changed code would break the
  read-only boundary, needs a build of the target repository, and invites flaky failures. The
  evidence gate stops fabricated citations, which is the failure that matters for a comment; a
  failing test would be stronger evidence, but it is a different product (`run_test` is the next
  tool if this ever stops being a demo).

## Evaluation

`mvn -q compile` then `java -cp "target/classes;<classpath>" com.codereview.agent.eval.EvalRunner`
replays 22 mutations through the whole pipeline and writes `eval/latest-report.md` and
`eval/latest-report.json`. Add `--real` to use the configured model instead of the stand-ins.

Real model (`deepseek-chat`, temperature 0), latest run of 22 cases:

| metric | value |
| --- | --- |
| cases | 47 |
| triage recall | 77% (24/31) |
| false-positive rate on clean changes | 6% (1/16) |
| findings | 25 |
| kept / downgraded / dropped | 14 / 16 / 0 |
| end-to-end recall, any comment near the line | 74% |
| end-to-end recall, comment cites evidence | 65% |

Widening the corpus cost recall: the earlier 22-case suite read 88%, this one reads 77%. The new
cases include behaviour changes no shape rule recognises (a swapped operand, a changed multiplier),
and the model declines more of them on the judging tier. A bigger corpus makes the number worse,
which is the direction it should move.

The two end-to-end rows are the same run. The wider one counts any comment within five lines of the
injected defect, including the fallback question posted when a must-investigate unit ends without
evidence. The narrower one counts only comments that cite evidence. Before the public-surface check
below, those two rows read 72% and 50%; after it, 74% and 65%.

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

### Ablation: what each stage earns

`AblationRunner` replays the same corpus at three depths — triage only, triage plus investigation,
and the full pipeline — and writes `eval/latest-ablation.md`. Real model (`deepseek-chat`):

| depth | defects caught | clean changes flagged | comments | findings | model calls | tokens |
|---|---|---|---|---|---|---|
| triage only | 13/16 (81%) | 1/6 (17%) | 21 | 0 | 39 | 17,290 |
| + investigation | 13/16 (81%) | 1/6 (17%) | 15 | 9 | 85 | 70,812 |
| + verification (full) | 13/16 (81%) | 1/6 (17%) | 16 | 12 | 101 | 88,379 |

Recall does not move across the three rows, because it is decided in triage: if a unit is never
scheduled, no later stage can rescue it. What the later stages buy is precision per comment — 21
comments from triage alone, 16 after evidence and a critic, with the same defects covered. That is
the trade to be argued about, so it is stated in the two numeric columns rather than in prose.

The cost is not small: investigation takes the run from 17k to 71k tokens, verification adds another
17k. Verification changed no verdict in this run, which is the honest state of that stage: it is
implemented, it is cheap relative to investigation, and it has not yet earned its cost on this
corpus. A critic that never changes anything is a cost with no measured benefit — the report prints
`changedByCritic` precisely so that this stays visible instead of being inferred from a zero.

To measure against a real model, set `CODEREVIEW_LLM_API_KEY` (and optionally
`CODEREVIEW_LLM_BASE_URL`, `CODEREVIEW_LLM_MODEL`) and add `--real`; the harness itself is unchanged.
Every run reports token usage through a process-wide ledger, so the cost of a stage is visible
instead of assumed.

## Posting to a pull request

```
java -cp "target/classes;<classpath>" com.codereview.agent.publish.PostReview \
     <repository> <base> <head> <owner/name> <pull number> [--real] [--dry-run]
```

Dry run is the default: it prints the payload and writes `eval/latest-review-payload.json`.
Posting needs `GITHUB_TOKEN` (fine-grained, pull-request write) and the explicit `--post` flag, so
nothing writes to somebody's pull request by accident.

GitHub only accepts an inline comment on a line the pull request changed, so the comments whose
lines fall outside the diff are moved into the review body instead of being dropped — a review that
silently swallows half its comments is worse than one that lists them. Every inline comment carries
the evidence ids it cites as a footnote, which is the same gate the pipeline enforces before the
publishing step ever runs.

## Demo

```
mvn -q compile
java -cp "target/classes;<classpath>" com.codereview.agent.web.DemoServer
```

Open <http://127.0.0.1:8099/>. The page lists the repository's recent commits, lets you pick a
range with one click per end, and shows the units it chose, the tier and outcome of each, what each
unit cost in tool calls and tokens, the files screening dropped and why, and the comments that
survived verification. The right panel edits a file and commits it, then reviews that commit
immediately — the fastest way to see the gate react to a change you just made.

Three depths are selectable, so the ablation table above can be reproduced by clicking rather than
by editing code. Screening is not a dead end: any dropped file can be pulled back into the run, and
the run reports how many tokens it burned. The server binds to loopback only and, by default, uses
the offline stand-ins so a click costs nothing; start it with `--real` to call the configured model
instead.

## Build

```bash
mvn test
```

Requires JDK 21. No API keys are needed for the test suite.

## License

MIT
