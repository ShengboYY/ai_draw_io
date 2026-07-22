# E0 readiness audit

Status: **READY**

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
- Independently reviewed core cases: 450
- Independent human review status: `double_reviewed`
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

The corpus lock is frozen: core targets, guard-suite minimums, structural checks and
independent review all pass. Validation comparisons may proceed. The repository-visible
legacy holdout has not been run here; the external final holdout is not yet materialized.
