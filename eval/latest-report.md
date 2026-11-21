# Code review agent — evaluation

Corpus: 22 mutations, 16 of them introduce a known defect, 6 are clean refactors.

Models: deepseek-chat at https://api.deepseek.com/v1

| metric | value |
|---|---|
| triage unit recall | 88% (14/16) |
| triage false positive rate | 17% (1/6) |
| rule-gated recall (MUST, no model involved) | 88% (14/16) |
| rule-gated false positives (MUST on clean changes) | 17% (1/6) |
| end-to-end recall (any comment near the line) | 81% (13/16) |
| end-to-end recall (comment cites evidence) | 50% (8/16) |
| findings | 10 |
| kept / downgraded / dropped | 7 / 9 / 0 |
| duplicates merged | 0 |
| invalid retractions | 0 |

> `kept` counts assertions, `downgraded` counts comments demoted to questions (by the critic, or because a must-investigate unit ended with no evidence). A critic that never retracts is a cost with no measured benefit; this table is what makes that visible.

| case | defective | tier | units | findings | kept | downgraded | dropped | merged | invalid | hit line |
|---|---|---|---|---|---|---|---|---|---|---|
| null-guard-removed | null-guard | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| exception-swallowed | exception | MUST_INVESTIGATE | 1 | 2 | 2 | 0 | 0 | 0 | 0 | yes |
| early-exit-removed | early-exit | MUST_INVESTIGATE | 1 | 1 | 0 | 1 | 0 | 0 | 0 | no |
| annotation-removed | contract | MUST_INVESTIGATE | 1 | 1 | 0 | 1 | 0 | 0 | 0 | yes |
| log-line-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| condition-inverted | condition | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| synchronized-added | concurrency | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | near, no evidence |
| local-renamed | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| constant-extracted | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| resource-unclosed | resource | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| query-concatenated | injection | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| comment-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| visibility-narrowed | contract | MUST_INVESTIGATE | 1 | 1 | 0 | 1 | 0 | 0 | 0 | yes |
| signature-changed | contract | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | near, no evidence |
| statements-reordered | clean | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | - |
| auth-null-checks-removed | security | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | near, no evidence |
| boundary-off-by-one | boundary | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | near, no evidence |
| negative-quantity-check-removed | validation | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | near, no evidence |
| guard-kept-but-logged | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| case-insensitive-password | security | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| password-logged | semantic-pii | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| validation-after-use | semantic-order | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
