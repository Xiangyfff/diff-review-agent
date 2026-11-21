# Code review agent — evaluation

Corpus: 22 mutations, 16 of them introduce a known defect, 6 are clean refactors.

Models: deepseek-chat at https://api.deepseek.com/v1

| metric | value |
|---|---|
| triage unit recall | 81% (13/16) |
| triage false positive rate | 0% (0/6) |
| rule-gated recall (MUST, no model involved) | 81% (13/16) |
| rule-gated false positives (MUST on clean changes) | 0% (0/6) |
| end-to-end recall | 63% (10/16) |
| findings | 13 |
| kept / downgraded / dropped | 13 / 0 / 0 |
| duplicates merged | 0 |
| invalid retractions | 0 |

> The offline stand-ins never decline. The triage stand-in raises a question for every structural change, so every clean case gets scheduled; the verifier stand-in retracts as soon as it reads a nearby null check, which is how a real defect was dropped below. These rows measure the wiring, not model quality. The rule-gated rows above are the part that does not depend on a model at all.

| case | defective | tier | units | findings | kept | downgraded | dropped | merged | invalid | hit line |
|---|---|---|---|---|---|---|---|---|---|---|
| null-guard-removed | null-guard | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| exception-swallowed | exception | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| early-exit-removed | early-exit | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | no |
| annotation-removed | contract | MUST_INVESTIGATE | 1 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| log-line-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| condition-inverted | condition | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| synchronized-added | concurrency | MUST_INVESTIGATE | 1 | 2 | 2 | 0 | 0 | 0 | 0 | yes |
| local-renamed | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| constant-extracted | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| resource-unclosed | resource | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| query-concatenated | injection | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| comment-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| visibility-narrowed | contract | MUST_INVESTIGATE | 1 | 2 | 2 | 0 | 0 | 0 | 0 | yes |
| signature-changed | contract | MUST_INVESTIGATE | 1 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| statements-reordered | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| auth-null-checks-removed | security | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| boundary-off-by-one | boundary | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| negative-quantity-check-removed | validation | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| guard-kept-but-logged | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| case-insensitive-password | security | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| password-logged | semantic-pii | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| validation-after-use | semantic-order | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
