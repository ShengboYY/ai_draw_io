# E0 readiness audit

Status: **BLOCKED**

## Current corpus

- Core cases: 450 / 450
- Development: 250 / 250
- Validation: 100 / 100
- Holdout: 100 / 100
- Generated cases including guards: 722
- Anchors: 402; documents: 39

## Blocking gaps

- Missing core cases: 0
- Split gaps: `{}`
- Independently reviewed core cases: 0
- Independent human review status: `pending`
- Primary-category deltas: `{}`
- Language-target deltas: `{}`
- Guard-suite deltas: `{}`

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
- PASS - `guardSuiteMinimumsMet`
- PASS - `generationTasksValid`
- PASS - `generationContextsValid`

The corpus lock is still a candidate. Do not run formal comparisons until all listed
count, label, guard-suite and independent-review gaps are closed.
