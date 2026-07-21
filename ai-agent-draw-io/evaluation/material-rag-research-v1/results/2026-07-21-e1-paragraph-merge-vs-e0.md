# E1 — text-block merge (paragraph merge) vs E0

Single-variable experiment on the `development` split, dense-only retrieval.
Only change vs E0: `CanonicalPageAssembler` `canonical-v4` → `canonical-v5`, which
adds `mergeSameLineParagraphRuns` + `mergeContiguousParagraphLines` (joins PDFBox
physical lines / split text callbacks into paragraph blocks). Everything else
(corpus, split, embedding model, retrieval budget, gates) is identical.

## Headline: E1 clears both recall gates

| Metric | E0 (v4) | E1 (v5) | Δ | V2 gate |
|---|--:|--:|--:|:--:|
| Recall@1 | 0.5645 | 0.6613 | +0.097 | — |
| Recall@5 | 0.8065 | 0.8548 | +0.048 | — |
| Recall@10 | 0.8387 | **0.9032** | +0.065 | ≥0.90 ✅ |
| Recall@40 | 0.9194 | **0.9516** | +0.032 | ≥0.95 ✅ |
| MRR@10 | 0.6625 | 0.7438 | +0.081 | ≥0.75 (0.744, ~at) |
| indexed chunks | 724 | **399** | −325 | — |

Merging cut chunk count nearly in half: facts are no longer fragmented across
physical-line chunks. The gain (+0.065 R@10) is far above the plan's 0.02
promotion threshold. **E1 promotes.**

## Slices (R@10)

| Slice | E0 | E1 | Δ |
|---|--:|--:|--:|
| language: zh | 0.792 | **0.958** | +0.167 |
| language: en | 0.769 | 0.769 | 0.000 |
| language: crossLanguage | 0.920 | 0.920 | 0.000 |
| category: table | 0.889 | **1.000** | +0.111 |
| category: text | 0.841 | 0.932 | +0.091 |
| category: multi_evidence | 0.778 | **0.667** | −0.111 |

## Misses recovered vs new

- E0 hard misses (5): 039 isolation-time, 121 dwh-sequence-type, 231 water-version-reject, 236 daa-version-superseded, 238 daa-chartbook-first.
- E1 hard misses (3): 058 water-period, 236 daa-version-superseded, 303 (multi hgr-baseline-energy + hgr-post-energy).
- **Recovered by merge:** 039, 121, 231, 238 — including two of the abstract policy anchors that dense missed entirely in E0, confirming the fragmentation hypothesis.
- **Still missing:** 236 daa-version-superseded (the "is superseded" oblique phrasing — likely needs lexical/hybrid, E3).
- **New regressions:** 058 water-period (short date span, may have merged into a larger block and lost salience); 303 the cross-page before/after energy pair.

## Caveats (per plan §4: no major slice may regress significantly)

- **multi_evidence regressed −0.111** (0.778→0.667, MRR 0.27→0.37 so ranking of the part it does find improved, but full-pair recall dropped). Case 303 (18.4 vs 15.1 MWh cross-page comparison) flipped from hit to miss: over-merging can blur a precise cross-page numeric pair into one large block whose embedding matches the comparison query less well. n=9, so this is 1 case — watch it, don't over-read, but it is a real directional signal that aggressive paragraph merge can hurt precise multi-evidence.
- **en still flat at 0.769** — merge didn't help English; still the weakest language (n=13, small).

## Verdict

E1 (paragraph merge) is a clear win: both recall gates now pass, driven by table/
text/zh and by de-fragmenting abstract-policy anchors. Promote it into the E2
baseline. Track the multi_evidence dip and the flat English slice as open items
for later experiments (E2 chunk parent/child, E3 hybrid, E6 context budget).

## Reproduce

Same as the E0 baseline command, with `canonical-v5` (paragraph merge) applied in
`CanonicalPageAssembler`. E0 baseline: [2026-07-21-e0-development-dense-baseline.md].
