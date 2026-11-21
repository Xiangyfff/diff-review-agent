# Ablation

Same corpus, same models (deterministic offline stand-ins), one stage removed per row.

| depth | defects caught | clean changes flagged | comments | findings | model calls | tokens |
| --- | --- | --- | --- | --- | --- | --- |
| TRIAGE_ONLY | 14/16 (88%) | 2/6 (33%) | 20 | 0 | 0 | 0 |
| WITH_INVESTIGATION | 12/16 (75%) | 2/6 (33%) | 17 | 20 | 0 | 0 |
| FULL | 11/16 (69%) | 2/6 (33%) | 16 | 20 | 0 | 0 |
