# E0 readiness audit

Status: **BLOCKED**

## Current corpus

- Core cases: 240 / 240
- Development: 120 / 120
- Validation: 60 / 60
- Holdout: 60 / 60
- Generated cases including guards: 309
- Anchors: 134; documents: 16

## Blocking gaps

- Missing core cases: 0
- Split gaps: `{}`
- Independently reviewed core cases: 0
- Independent human review status: `pending`
- Primary-category deltas: `{"crossLanguage": -12, "exactLookup": -64, "failure": 2, "multiEvidence": 8, "noAnswerAndMisleading": 8, "ocr": 6, "retrievalDecision": 10, "versionAndAuthorization": 16, "visual": 26}`
- Language-target deltas: `{"crossLanguage": -26, "en": -3, "zh": 29}`

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
