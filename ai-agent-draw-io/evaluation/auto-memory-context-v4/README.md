# V1.7.3 Memory Context V4 pre-registration

## Pre-run status

The development-v3 cohort and untouched V4 holdout were frozen in `a7b36ac0`, before the V1.7.3
candidate policy was implemented. V1, V2 and V3 were not reused for parameter selection. This file
and the implementation are committed before the first V4 query.

## Frozen data

- Development-v3 SHA-256:
  `5347df5d500c9632c97f34a2f561be18d4808d1e41c71a3916f98274a6b2a900`
- V4 holdout SHA-256:
  `9f7d173ea029478dea137227e72b6fd30c674792c8d20510913802df0f3fdfab`
- Each cohort contains 45 synthetic Memory records, six single-target positive cases, two
  dual-target positive cases and eight negative operation cases.
- USER/CHARTBOOK same-key override is represented independently in both cohorts.

## Development comparison

| Candidate policy | Recall@12 | Irrelevant selection | Negative-case selection | Mean selected |
|---|---:|---:|---:|---:|
| All hits above `0.82` | 70.0% | 58.8% | 12.5% | 1.0625 |
| Top-1 only | 60.0% | 14.3% | 12.5% | 0.4375 |
| Top-relative `0.03` + broad-cohort rejection | 70.0% | 12.5% | 0.0% | 0.5000 |

The selected policy preserves the V1.7.2 query gate (`minimumScore=0.82`, `minimumLead=0.02`). Once
a query is accepted, candidates must also be within `0.03` of Top-1. A cohort wider than four is
treated as ambiguous and returns empty instead of silently truncating. The two development misses
occurred before candidate inclusion and were not patched in this stage.

## V4 quality gate

| Metric | Required |
|---|---:|
| Recall@1 | >= 60.0% |
| Recall@3 | >= 80.0% |
| Recall@12 | >= 90.0% |
| Positive-case hit rate | >= 87.5% |
| MRR | >= 0.75 |
| Recall@3 lift over SQL | >= 70.0 points |
| Irrelevant selection rate | <= 20.0% |
| Negative-case selection rate | <= 25.0% |
| Forbidden selection rate | 0% |
| Unauthorized selection count | 0 |

V4 may be run once. It cannot be used to alter the policy, gate or labels and then be rerun. The
evaluator uses only synthetic text, performs zero DeepSeek calls and zero MySQL/business writes,
and must use an isolated Pinecone namespace containing `eval`. Every vector ID is deleted and
verified absent in `finally`. Semantic Memory Context remains disabled regardless of a synthetic
pass until a separately authorized local-real-data stage is completed.

## First-run outcome

The only permitted V4 run completed on 2026-08-02 and did not pass. Candidate precision and
abstention passed cleanly, but Recall@3/12, recall lift and positive-case hit rate missed their
pre-registered gates. The policy was not changed and V4 was not rerun. Full evidence is recorded in
`results/2026-08-02-relative-cohort-multilingual-e5-large.md`.
