# E0 readiness audit

Status: **READY**

## Current corpus

- Core cases: 240 / 240
- Development: 120 / 120
- Validation: 60 / 60
- Holdout: 60 / 60
- Generated cases including guards: 379
- Anchors: 233; documents: 24

## Blocking gaps

- Missing core cases: 0
- Split gaps: `{}`
- Independently reviewed core cases: 240
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
