# Code review agent — evaluation

Corpus: 2 mutations, 2 of them introduce a known defect, 0 are clean refactors.

Models: deepseek-chat at https://api.deepseek.com/v1

| metric | value |
|---|---|
| triage unit recall | 100% (2/2) |
| triage false positive rate | 0% (0/0) |
| rule-gated recall (MUST, no model involved) | 100% (2/2) |
| rule-gated false positives (MUST on clean changes) | 0% (0/0) |
| end-to-end recall (any comment near the line) | 100% (2/2) |
| end-to-end recall (comment cites evidence) | 100% (2/2) |
| findings | 2 |
| kept / downgraded / dropped | 2 / 0 / 0 |
| duplicates merged | 0 |
| invalid retractions | 0 |

> `kept` counts assertions, `downgraded` counts comments demoted to questions (by the critic, or because a must-investigate unit ended with no evidence). A critic that never retracts is a cost with no measured benefit; this table is what makes that visible.

| case | defective | tier | units | findings | kept | downgraded | dropped | merged | invalid | hit line |
|---|---|---|---|---|---|---|---|---|---|---|
| policy-plain-equals | policy-security | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| policy-stdout-in-audit | policy-logging | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
