# Sampling what the gate skipped

The evaluation only sees units the program decided to investigate, so by construction it cannot see
what the program threw away. `SamplingSweep` takes the defect cases whose triage tier was `SKIP`,
forces the sampling rate to 100% and reviews them at full depth. The model is `deepseek-chat`; each
round uses a different seed so the sample is not fixed to one draw.

## Round over the seven skipped defects (seed 100)

| case | sampled | comments | hit | model calls | tokens |
|---|---|---|---|---|---|
| discount-magic-number | 1 | 1 | yes | 5 | 6,873 |
| discount-bulk-multiplier | 1 | 1 | yes | 6 | 8,758 |
| shipping-zone-multiplier | 1 | 0 | no | 5 | 7,547 |
| null-added-to-collection | 1 | 1 | yes | 7 | 10,671 |
| require-nonnull-dropped | 1 | 1 | yes | 8 | 13,691 |
| not-found-returns-null | 1 | 1 | yes | 6 | 9,683 |
| query-concatenated | 1 | 1 | no (comment off the injected line) | 4 | 5,628 |

Caught 5 of 7. The two misses are a multiplier in a different method of the same unit and a
conclusion that named the right defect but sat on a line outside the tolerance.

## Round over three of the same defects (seed 101)

| case | sampled | comments | hit | model calls | tokens |
|---|---|---|---|---|---|
| discount-magic-number | 1 | 0 | no | 5 | 7,149 |
| not-found-returns-null | 1 | 1 | yes | 5 | 7,526 |
| require-nonnull-dropped | 1 | 1 | yes | 10 | 15,771 |

Caught 2 of 3. The same case that was caught in the first round was missed in the second, which is
the honest shape of a single-sample measurement: four to five in seven, not a rate.

## What was done with it

The gate was reading "small" as "safe". Two shapes now decide those units without a model call:
`LITERAL-CHANGED` and `NULL-ARGUMENT`, plus removed guards that were assignments of a checked value.
Re-probing the seven offline, six reach must-investigate. Cost on clean changes: four of sixteen
clean cases became scheduled — two judge-tier (the model still declines), one deliberate reorder,
and one log rewording that has since been fixed.
