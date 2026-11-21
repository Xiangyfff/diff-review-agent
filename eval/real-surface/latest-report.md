# Code review agent — evaluation

Corpus: 47 mutations, 31 of them introduce a known defect, 16 are clean refactors.

Models: deepseek-chat at https://api.deepseek.com/v1

| metric | value |
|---|---|
| triage unit recall | 77% (24/31) |
| triage false positive rate | 6% (1/16) |
| rule-gated recall (MUST, no model involved) | 74% (23/31) |
| rule-gated false positives (MUST on clean changes) | 6% (1/16) |
| end-to-end recall (any comment near the line) | 74% (23/31) |
| end-to-end recall (comment cites evidence) | 65% (20/31) |
| findings | 25 |
| kept / downgraded / dropped | 14 / 16 / 0 |
| duplicates merged | 0 |
| invalid retractions | 2 |

> `kept` counts assertions, `downgraded` counts comments demoted to questions (by the critic, or because a must-investigate unit ended with no evidence). A critic that never retracts is a cost with no measured benefit; this table is what makes that visible.

| case | defective | tier | units | findings | kept | downgraded | dropped | merged | invalid | hit line |
|---|---|---|---|---|---|---|---|---|---|---|
| null-guard-removed | null-guard | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| exception-swallowed | exception | MUST_INVESTIGATE | 1 | 2 | 2 | 0 | 0 | 0 | 0 | yes |
| early-exit-removed | early-exit | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | no |
| annotation-removed | contract | MUST_INVESTIGATE | 1 | 1 | 0 | 1 | 0 | 0 | 0 | yes |
| log-line-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| condition-inverted | condition | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| synchronized-added | concurrency | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | near, no evidence |
| local-renamed | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| constant-extracted | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| resource-unclosed | resource | JUDGE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| query-concatenated | injection | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| comment-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| visibility-narrowed | contract | MUST_INVESTIGATE | 1 | 1 | 0 | 1 | 0 | 0 | 0 | yes |
| signature-changed | contract | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | near, no evidence |
| statements-reordered | clean | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | - |
| auth-null-checks-removed | security | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| boundary-off-by-one | boundary | MUST_INVESTIGATE | 1 | 1 | 0 | 1 | 0 | 0 | 0 | yes |
| negative-quantity-check-removed | validation | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| guard-kept-but-logged | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| case-insensitive-password | security | MUST_INVESTIGATE | 1 | 0 | 0 | 1 | 0 | 0 | 0 | near, no evidence |
| password-logged | semantic-pii | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| validation-after-use | semantic-order | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| audit-visibility-widened | contract | MUST_INVESTIGATE | 1 | 2 | 0 | 2 | 0 | 0 | 0 | yes |
| validate-guard-removed | consistency | MUST_INVESTIGATE | 1 | 2 | 1 | 1 | 0 | 0 | 0 | yes |
| stub-comment-reworded | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| tax-added-instead-of-subtracted | arithmetic | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| discount-magic-number | arithmetic | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| discount-bulk-multiplier | arithmetic | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| auth-always-true | security | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| handle-made-public | contract | MUST_INVESTIGATE | 1 | 2 | 0 | 2 | 0 | 0 | 2 | yes |
| not-found-returns-null | semantic | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| null-added-to-collection | semantic | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| save-call-renamed | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| list-fully-qualified | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| log-message-reworded | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| shipping-zone-multiplier | arithmetic | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| multiply-operands-swapped | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| field-commented | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| require-nonnull-dropped | consistency | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| read-all-swallows-io | exception | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| password-content-equals | security | MUST_INVESTIGATE | 1 | 1 | 0 | 1 | 0 | 0 | 0 | yes |
| submit-zone-parameter-dropped | contract | MUST_INVESTIGATE | 1 | 2 | 0 | 2 | 0 | 0 | 0 | yes |
| line-total-guard-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| list-method-commented | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| unused-import-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| trailing-whitespace | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| tax-multiplied | arithmetic | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
