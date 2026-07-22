# E0 readiness audit

Status: **READY**

## Current corpus

- Core cases: 450 / 240
- Development: 250 / 120
- Validation: 100 / 60
- Holdout: 100 / 60
- Generated cases including guards: 711
- Anchors: 402; documents: 39

## Blocking gaps

- Missing core cases: 0
- Split gaps: `{}`
- Independently reviewed core cases: 450
- Independent human review status: `double_reviewed`
- Primary-category deltas: `{}`
- Language-target deltas: `{}`

## Structural checks

- PASS - `uniqueCaseIds`
- PASS - `uniqueAnchorIds`
- PASS - `allGoldAnchorsResolve`
- PASS - `allowedSourcesMatchAnchors`
- PASS - `caseAnchorMetadataMatches`
- PASS - `evidenceGradesValid`
- PASS - `evidenceGroupsStructurallyValid`
- PASS - `requiredEvidenceMetadataMatches`
- PASS - `requiredEvidenceMatchesGoldAnchors`
- PASS - `allowedSourceVersionsExist`
- PASS - `answerableCasesHaveExpectedAnswer`
- PASS - `noAnswerCasesHaveAbstentionCondition`
- PASS - `documentFamiliesDoNotCrossSplits`
- PASS - `reviewLedgerReferencesKnownCases`
- PASS - `primaryCategoryLabelsValid`
- PASS - `scenarioCategoryContextsValid`

The candidate lock records current SHA-256 inputs but is not a frozen E0 lock. Do not run
E0 or resume E1 until the case-count, label and independent-review gaps are closed.
