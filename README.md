# diff-review-agent

A code review agent that reads a git diff, decides which hunks are worth investigating, and only
leaves a comment when it can point at the evidence it found.

Review bots usually fail in three ways: they comment before reading the surrounding code, they never
leave the diff to look at the caller, and nobody can say afterwards whether their comments were
right. This project is built around those three failures.

## How it works

```
diff ──> triage ──> investigate ──> verify ──> comments
```

**Triage** parses the patch, cuts the changes into function-sized units, compares the before and
after syntax tree of each unit, and runs a small rule catalogue over the *changes themselves* — a
null check that disappeared is a different event from one that was never there. Rules that demand
attention produce concrete questions the unit has to answer before it may be commented on. Units
that do not earn a question are dropped; units that do are scheduled under a budget.

**Investigate** (next) takes only the scheduled units, lets the agent loop over read-only tools under
a hard budget, and produces findings that carry evidence ids.

**Verify** (after that) is a critic that tries to refute each finding. Anything it can refute is
dropped.

## Status

- [x] Triage — patch parsing, unit mapping, structural change detection, rule catalogue, tiering,
      question validation, budgeted plan, deterministic offline model
- [x] 36 tests, no network required
- [ ] Investigate — tool loop, tool budget, evidence store
- [ ] Verify — adversarial critic
- [ ] Evaluation — mutation-based oracle and an ablation table
- [ ] LangChain4j model adapter

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
- **The model layer is a thin interface.** Tests run against a deterministic offline model; the
  LangChain4j adapter is one implementation behind it.

## Build

```bash
mvn test
```

Requires JDK 21. No API keys are needed for the test suite.

## License

MIT
