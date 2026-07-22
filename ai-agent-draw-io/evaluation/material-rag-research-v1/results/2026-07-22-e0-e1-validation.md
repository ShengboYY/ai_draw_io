# E0/E1 paired validation comparison on the frozen 450-case corpus

Paired dense-retrieval cases: **73**. Holdout remained sealed.
All intervals below are 95%; recall uses Wilson intervals and paired deltas/MRR use a
deterministic 10,000-sample percentile bootstrap.

| Metric | E0 | E0 CI | E1 | E1 CI | Delta | Delta CI |
|---|---:|:---:|---:|:---:|---:|:---:|
| recallAt1 | 0.466 | [0.356, 0.579] | 0.548 | [0.434, 0.657] | 0.082 | [-0.027, 0.205] |
| recallAt5 | 0.644 | [0.529, 0.744] | 0.753 | [0.644, 0.838] | 0.110 | [0.027, 0.205] |
| recallAt10 | 0.699 | [0.586, 0.792] | 0.836 | [0.734, 0.903] | 0.137 | [0.068, 0.219] |
| recallAt40 | 0.726 | [0.614, 0.815] | 0.849 | [0.750, 0.914] | 0.123 | [0.055, 0.205] |
| mrrAt10 | 0.545 | [0.443, 0.648] | 0.651 | [0.555, 0.743] | 0.106 | [0.019, 0.197] |

## Recall@10 slices

| Slice | n | E0 (95% CI) | E1 (95% CI) | Delta (paired 95% CI) |
|---|---:|:---:|:---:|:---:|
| category:multi_evidence | 13 | 0.692 [0.424, 0.873] | 0.846 [0.578, 0.957] | 0.154 [0.000, 0.385] |
| category:table | 8 | 1.000 [0.676, 1.000] | 1.000 [0.676, 1.000] | 0.000 [0.000, 0.000] |
| category:text | 52 | 0.654 [0.518, 0.768] | 0.808 [0.681, 0.892] | 0.154 [0.058, 0.250] |
| language:crossLanguage | 16 | 1.000 [0.806, 1.000] | 1.000 [0.806, 1.000] | 0.000 [0.000, 0.000] |
| language:en | 26 | 0.692 [0.500, 0.835] | 0.885 [0.710, 0.960] | 0.192 [0.038, 0.346] |
| language:zh | 31 | 0.548 [0.378, 0.708] | 0.710 [0.534, 0.839] | 0.161 [0.032, 0.290] |
| primaryCategory:crossLanguage | 12 | 1.000 [0.758, 1.000] | 1.000 [0.758, 1.000] | 0.000 [0.000, 0.000] |
| primaryCategory:exactLookup | 6 | 1.000 [0.610, 1.000] | 1.000 [0.610, 1.000] | 0.000 [0.000, 0.000] |
| primaryCategory:failure | 3 | 0.000 [0.000, 0.561] | 1.000 [0.439, 1.000] | 1.000 [1.000, 1.000] |
| primaryCategory:multiEvidence | 13 | 0.692 [0.424, 0.873] | 0.846 [0.578, 0.957] | 0.154 [0.000, 0.385] |
| primaryCategory:retrievalDecision | 17 | 0.471 [0.262, 0.690] | 0.706 [0.469, 0.867] | 0.235 [0.059, 0.471] |
| primaryCategory:versionAndAuthorization | 22 | 0.727 [0.518, 0.868] | 0.773 [0.566, 0.899] | 0.045 [0.000, 0.136] |

## Decision

E1 improves Recall@10 by 0.137, Recall@40 by 0.123, and MRR@10 by 0.106. The improvement generalizes to Validation, but E1 remains below the 0.90/0.95/0.75 promotion gates. Keep the representation change as a proven component; do not declare the dense-only pipeline complete. Continue with E2 or E3 on Development, then return to Validation.
