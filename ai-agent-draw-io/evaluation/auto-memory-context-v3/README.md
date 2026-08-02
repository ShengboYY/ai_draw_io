# V1.7.2 Memory Context V3 pre-registration

## Pre-registered status

Before the first V3 query, its labels, quality gate and SHA-256 were frozen with the development-v2
cohort in commit `22ce4202`, before the V1.7.2 acceptance policy was implemented. V1 and V2 were not
reused for parameter selection.

## Frozen data

- Development-v2 SHA-256:
  `48f55a6830993e7474da10f11b461dae7d8e64fe3ee8efa5f3d409f107185eb5`
- V3 holdout SHA-256:
  `e19cb9a885132e1bdcb3ee41c1cf3c5409154f6b00df866a9ce3d838dcb0bd64`
- Each cohort contains 43 synthetic Memory records, eight positive cases and eight negative cases.
- The cohorts use different target topics, distractor banks and operational negative queries.
- A negative case has an explicit empty relevance label and should inject no Memory.

## Policy selected on development-v2

1. The top score must be at least `0.82`.
2. If the second score is also at least `0.82`, retain every hit at or above that floor.
3. Otherwise the top score must lead the second score by at least `0.02`.
4. An uncertain or weak result returns no Memory; a vector failure still uses the SQL fallback.

Development-v2 produced Recall@1 75%, Recall@3/12 87.5%, MRR 0.8125, irrelevant selection rate
12.5%, and negative-case selection rate 0%. The one missed positive was not patched specially.

## V3 quality gate

| Metric | Required |
|---|---:|
| Recall@1 | >= 75.0% |
| Recall@3 | >= 87.5% |
| Recall@12 | >= 87.5% |
| MRR | >= 0.80 |
| Recall@3 lift over SQL | >= 75.0 points |
| Irrelevant selection rate | <= 25.0% |
| Negative-case selection rate | <= 25.0% |
| Forbidden selection rate | 0% |
| Unauthorized selection count | 0 |

V3 is a one-pass release gate. Regardless of its outcome, its result must not be used to change the
policy and rerun the same holdout. Inputs remain synthetic, the evaluator writes no business/MySQL
data and calls no DeepSeek model. Pinecone data must use an isolated namespace containing `eval` and
must be deleted and verified absent in the evaluator's `finally` block.

## First-run outcome

The one permitted V3 run was completed on 2026-08-02 against the frozen policy. It did not pass:
all gates passed except irrelevant selection rate, which was 30% against the pre-registered 25%
maximum. The policy was not changed and V3 was not rerun. Full evidence and interpretation are in
`results/2026-08-02-confidence-gated-multilingual-e5-large.md`.
