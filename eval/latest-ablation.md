# Ablation

Same corpus, same models (deepseek-chat at https://api.deepseek.com/v1), one stage removed per row.

| depth | defects caught | clean changes flagged | comments | findings | model calls | tokens |
| --- | --- | --- | --- | --- | --- | --- |
| TRIAGE_ONLY | 13/16 (81%) | 1/6 (17%) | 21 | 0 | 39 | 17290 |
| WITH_INVESTIGATION | 13/16 (81%) | 1/6 (17%) | 15 | 9 | 85 | 70812 |
| FULL | 13/16 (81%) | 1/6 (17%) | 16 | 12 | 101 | 88379 |
