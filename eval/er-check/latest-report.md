# Code review agent — evaluation

Corpus: 48 mutations, 32 of them introduce a known defect, 16 are clean refactors.

Models: deterministic offline stand-ins

| metric | value |
|---|---|
| triage unit recall | 78% (25/32) |
| triage false positive rate | 19% (3/16) |
| rule-gated recall (MUST, no model involved) | 75% (24/32) |
| rule-gated false positives (MUST on clean changes) | 6% (1/16) |
| end-to-end recall (any comment near the line) | 59% (19/32) |
| end-to-end recall (comment cites evidence) | 59% (19/32) |
| findings | 34 |
| kept / downgraded / dropped | 28 / 0 / 0 |
| duplicates merged | 6 |
| invalid retractions | 0 |

> The offline stand-ins never decline. The triage stand-in raises a question for every structural change, so every clean case gets scheduled; the verifier stand-in retracts as soon as it reads a nearby null check, which is how a real defect was dropped below. These rows measure the wiring, not model quality. The rule-gated rows above are the part that does not depend on a model at all.

| case | defective | tier | units | findings | kept | downgraded | dropped | merged | invalid | hit line |
|---|---|---|---|---|---|---|---|---|---|---|
| null-guard-removed | null-guard | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| exception-swallowed | exception | MUST_INVESTIGATE | 1 | 2 | 1 | 0 | 0 | 1 | 0 | no |
| early-exit-removed | early-exit | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | no |
| annotation-removed | contract | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| log-line-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| condition-inverted | condition | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | no |
| synchronized-added | concurrency | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| local-renamed | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| constant-extracted | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| resource-unclosed | resource | JUDGE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| query-concatenated | injection | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| comment-added | clean | JUDGE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | - |
| visibility-narrowed | contract | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| signature-changed | contract | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| statements-reordered | clean | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | - |
| auth-null-checks-removed | security | MUST_INVESTIGATE | 1 | 2 | 1 | 0 | 0 | 1 | 0 | yes |
| boundary-off-by-one | boundary | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| negative-quantity-check-removed | validation | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| guard-kept-but-logged | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| case-insensitive-password | security | MUST_INVESTIGATE | 1 | 2 | 1 | 0 | 0 | 1 | 0 | yes |
| password-logged | semantic-pii | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| validation-after-use | semantic-order | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| audit-visibility-widened | contract | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| validate-guard-removed | consistency | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| stub-comment-reworded | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| tax-added-instead-of-subtracted | arithmetic | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | no |
| discount-magic-number | arithmetic | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| discount-bulk-multiplier | arithmetic | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| auth-always-true | security | MUST_INVESTIGATE | 1 | 2 | 1 | 0 | 0 | 1 | 0 | yes |
| handle-made-public | contract | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| not-found-returns-null | semantic | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| null-added-to-collection | semantic | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| save-call-renamed | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| list-fully-qualified | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| log-message-reworded | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| shipping-zone-multiplier | arithmetic | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| multiply-operands-swapped | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| field-commented | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| require-nonnull-dropped | consistency | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | no |
| read-all-swallows-io | exception | MUST_INVESTIGATE | 1 | 2 | 1 | 0 | 0 | 1 | 0 | no |
| password-content-equals | security | MUST_INVESTIGATE | 1 | 2 | 1 | 0 | 0 | 1 | 0 | yes |
| submit-zone-parameter-dropped | contract | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
| line-total-guard-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| list-method-commented | clean | JUDGE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | - |
| unused-import-added | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| trailing-whitespace | clean | SKIP | 0 | 0 | 0 | 0 | 0 | 0 | 0 | - |
| tax-multiplied | arithmetic | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | no |
| guard-returning-zero | boundary | MUST_INVESTIGATE | 1 | 1 | 1 | 0 | 0 | 0 | 0 | yes |
