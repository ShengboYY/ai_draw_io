#!/usr/bin/env python3
"""Audit material-RAG corpus readiness and emit a deterministic corpus lock."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CORE_SPLITS = ("development", "validation", "holdout")
LOCK_FILENAME = "corpus-lock.json"
GUARD_SPLITS = {
    "authorization": "guard_authorization",
    "versioning": "guard_versioning",
    "abstention": "guard_abstention",
    "visualAndOcr": "guard_visual_ocr",
    "failureAndRecovery": "guard_failure",
}
PROVENANCE_FILES = (
    "EXPERIMENT-PLAN-V2.md",
    "experiment-plan-v2.json",
    "requirements.txt",
    "analysis/audit_corpus.py",
    "analysis/compare_dense_runs.py",
    "analysis/audit_e4_chartbook.py",
    "analysis/evaluate_guard_suites.py",
    "analysis/evaluate_ocr.py",
    "fixtures/generate_fixtures.py",
    "fixtures/generation-config.json",
    "fixtures/realistic_corpus_specs.py",
    "fixtures/drawio_agent_corpus_specs.py",
    "fixtures/scenario_corpus_specs.py",
    "fixtures/guard_corpus_specs.py",
    "fixtures/expansion_corpus_specs.py",
    "fixtures/e4_chartbook_specs.py",
    "fixtures/query-selection.json",
    "fixtures/drawio-generation-tasks-v1.json",
)
CATEGORY_CONTEXT_FIELDS = {
    "failure": (
        "injectedDependency", "injectedState", "expectedSystemBehavior", "forbiddenSystemBehavior",
    ),
    "versionAndAuthorization": (
        "scenarioType", "actingRole", "scopeConstraint", "expectedDecision",
    ),
}


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def generated_hashes(generated_root: Path) -> dict[str, str]:
    """Hash every generated input except the lock file that contains the hashes."""
    return {
        path.relative_to(generated_root).as_posix(): sha256(path)
        for path in sorted(generated_root.rglob("*"))
        if path.is_file() and path.name not in {LOCK_FILENAME, "corpus-lock.candidate.json"}
    }


def provenance_hashes(root: Path) -> dict[str, str]:
    """Lock the authored plan, generator, evaluator and dependency declaration."""
    return {relative: sha256(root / relative) for relative in PROVENANCE_FILES}


def duplicate_values(values: list[str]) -> list[str]:
    counts = Counter(values)
    return sorted(value for value, count in counts.items() if count > 1)


def evidence_shape_errors(case: dict) -> list[str]:
    """Reject cases that Java would drop from the retrieval denominator."""
    errors: list[str] = []
    gold_anchor_ids = case.get("goldAnchorIds", [])
    groups = case.get("requiredEvidenceGroups", [])
    if case.get("answerable"):
        if not gold_anchor_ids:
            errors.append("answerable case has no gold anchors")
        if not groups:
            errors.append("answerable case has no evidence groups")
    elif gold_anchor_ids or groups:
        errors.append("no-answer case must not carry gold evidence")
    for group in groups:
        if group.get("operator") not in {"ANY", "ALL_PARTS"}:
            errors.append("unsupported evidence-group operator")
        if not group.get("evidence"):
            errors.append("evidence group is empty")
    return errors


def reviewed_case_ids(review_ledger: Path | None) -> tuple[set[str], str, str | None]:
    """Count only auditable agreements from two distinct named reviewers."""
    if review_ledger is None or not review_ledger.exists():
        return set(), "pending", None
    ledger = json.loads(review_ledger.read_text(encoding="utf-8"))
    reviewed = {
        entry["caseId"]
        for entry in ledger.get("cases", [])
        if entry.get("status") in {"agreed", "arbitrated"}
        and len(set(entry.get("reviewers", []))) >= 2
    }
    return reviewed, "double_reviewed" if reviewed else "pending", sha256(review_ledger)


def audit(root: Path, review_ledger: Path | None) -> tuple[dict, dict]:
    generated = root / "fixtures" / "generated"
    plan = json.loads((root / "experiment-plan-v2.json").read_text(encoding="utf-8"))
    manifest = json.loads((generated / "corpus-manifest.json").read_text(encoding="utf-8"))
    anchors = json.loads((generated / "ground-truth.json").read_text(encoding="utf-8"))["anchors"]
    cases = read_jsonl(generated / "cases.jsonl")
    reviewed_ids, _, review_ledger_sha = reviewed_case_ids(review_ledger)

    documents = {document["source"]: document for document in manifest["documents"]}
    known_source_versions = {
        f"{document['source']}:{document['version']}" for document in manifest["documents"]
    }
    anchor_by_id = {anchor["anchorId"]: anchor for anchor in anchors}
    core_targets = plan["coreCases"]
    core_cases = [case for case in cases if case.get("split") in CORE_SPLITS]
    guard_counts = Counter(
        case.get("split") for case in cases if case.get("split", "").startswith("guard_")
    )
    core_case_ids = {case["caseId"] for case in core_cases}
    reviewed_core_ids = reviewed_ids & core_case_ids
    human_review_status = (
        "double_reviewed" if core_case_ids and reviewed_core_ids == core_case_ids
        else "partial" if reviewed_core_ids else "pending"
    )
    unknown_reviewed_case_ids = sorted(reviewed_ids - {case["caseId"] for case in cases})
    split_counts = Counter(case["split"] for case in core_cases)
    language_counts = Counter(case["language"] for case in core_cases)
    category_counts = Counter(case.get("primaryCategory") for case in core_cases)
    modality_counts = Counter(case["category"] for case in core_cases)

    unresolved_anchors: list[dict] = []
    source_mismatches: list[dict] = []
    metadata_mismatches: list[dict] = []
    invalid_evidence_grades: list[dict] = []
    invalid_evidence_shapes: list[dict] = []
    evidence_metadata_mismatches: list[dict] = []
    evidence_group_gold_mismatches: list[dict] = []
    unknown_allowed_source_versions: list[dict] = []
    invalid_category_contexts: list[dict] = []
    missing_answers: list[str] = []
    missing_abstention_conditions: list[str] = []
    family_splits: defaultdict[str, set[str]] = defaultdict(set)

    for case in cases:
        family_splits[case["documentFamily"]].add(case["split"])
        shape_errors = evidence_shape_errors(case)
        if shape_errors:
            invalid_evidence_shapes.append({"caseId": case["caseId"], "errors": shape_errors})
        if case["answerable"] and not case.get("expectedAnswer"):
            missing_answers.append(case["caseId"])
        if not case["answerable"] and not case.get("abstentionCondition"):
            missing_abstention_conditions.append(case["caseId"])
        allowed_sources = set(case.get("allowedSourceVersions", []))
        for source_version in sorted(allowed_sources - known_source_versions):
            unknown_allowed_source_versions.append({
                "caseId": case["caseId"], "sourceVersion": source_version,
            })
        for anchor_id in case.get("goldAnchorIds", []):
            anchor = anchor_by_id.get(anchor_id)
            if anchor is None:
                unresolved_anchors.append({"caseId": case["caseId"], "anchorId": anchor_id})
                continue
            source_version = f"{anchor['source']}:{anchor['version']}"
            if source_version not in allowed_sources:
                source_mismatches.append({
                    "caseId": case["caseId"], "anchorId": anchor_id,
                    "sourceVersion": source_version,
                })
            if (anchor.get("split") != case.get("split")
                    or anchor.get("documentFamily") != case.get("documentFamily")):
                metadata_mismatches.append({"caseId": case["caseId"], "anchorId": anchor_id})
        required_anchor_ids: set[str] = set()
        for group in case.get("requiredEvidenceGroups", []):
            for requirement in group.get("evidence", []):
                anchor_id = requirement.get("anchorId")
                if isinstance(anchor_id, str):
                    required_anchor_ids.add(anchor_id)
                grade = requirement.get("grade")
                minimum_grade = requirement.get("minimumGrade")
                if anchor_id not in anchor_by_id or not isinstance(grade, int) \
                        or not isinstance(minimum_grade, int) or not 0 <= grade <= 3 \
                        or not 0 <= minimum_grade <= 3 or grade < minimum_grade:
                    invalid_evidence_grades.append({
                        "caseId": case["caseId"], "anchorId": anchor_id,
                        "grade": grade, "minimumGrade": minimum_grade,
                    })
                    continue
                anchor = anchor_by_id[anchor_id]
                source_version = f"{anchor['source']}:{anchor['version']}"
                if source_version not in allowed_sources \
                        or anchor.get("split") != case.get("split") \
                        or anchor.get("documentFamily") != case.get("documentFamily"):
                    evidence_metadata_mismatches.append({
                        "caseId": case["caseId"], "anchorId": anchor_id,
                        "sourceVersion": source_version,
                    })
        gold_anchor_ids = set(case.get("goldAnchorIds", []))
        if required_anchor_ids != gold_anchor_ids:
            evidence_group_gold_mismatches.append({
                "caseId": case["caseId"],
                "goldAnchorIds": sorted(gold_anchor_ids),
                "requiredAnchorIds": sorted(required_anchor_ids),
            })

        required_context_fields = CATEGORY_CONTEXT_FIELDS.get(case.get("primaryCategory"))
        if required_context_fields:
            context = case.get("evaluationContext")
            missing_fields = [
                field for field in required_context_fields
                if not isinstance(context, dict) or not str(context.get(field, "")).strip()
            ]
            if missing_fields:
                invalid_category_contexts.append({
                    "caseId": case["caseId"], "missingFields": missing_fields,
                })

    manifest_family_splits: defaultdict[str, set[str]] = defaultdict(set)
    for document in manifest["documents"]:
        manifest_family_splits[document["documentFamily"]].add(document["split"])
    combined_family_splits: defaultdict[str, set[str]] = defaultdict(set)
    for family, splits in [*manifest_family_splits.items(), *family_splits.items()]:
        combined_family_splits[family].update(splits)
    split_leakage = {
        family: sorted(splits)
        for family, splits in combined_family_splits.items()
        if len(splits) > 1
    }

    exact_split_counts = all(split_counts[split] == target for split, target in core_targets.items())
    valid_primary_categories = set(plan["primaryCategories"])
    primary_categories_valid = all(
        case.get("primaryCategory") in valid_primary_categories for case in cases
    )
    exact_category_counts = all(
        category_counts[category] == target
        for category, target in plan["primaryCategories"].items()
    )
    exact_language_counts = all(
        language_counts[language] == target
        for language, target in plan["languageTargets"].items()
    )
    guard_suite_counts = {
        suite: guard_counts[split] for suite, split in GUARD_SPLITS.items()
    }
    guard_suite_gaps = {
        suite: target - guard_suite_counts[suite]
        for suite, target in plan["guardSuites"].items()
        if guard_suite_counts[suite] < target
    }
    guard_suite_minimums_met = not guard_suite_gaps
    structural_checks = {
        "uniqueCaseIds": not duplicate_values([case["caseId"] for case in cases]),
        "uniqueAnchorIds": not duplicate_values([anchor["anchorId"] for anchor in anchors]),
        "allGoldAnchorsResolve": not unresolved_anchors,
        "allowedSourcesMatchAnchors": not source_mismatches,
        "caseAnchorMetadataMatches": not metadata_mismatches,
        "evidenceGradesValid": not invalid_evidence_grades,
        "evidenceGroupsStructurallyValid": not invalid_evidence_shapes,
        "requiredEvidenceMetadataMatches": not evidence_metadata_mismatches,
        "requiredEvidenceMatchesGoldAnchors": not evidence_group_gold_mismatches,
        "allowedSourceVersionsExist": not unknown_allowed_source_versions,
        "answerableCasesHaveExpectedAnswer": not missing_answers,
        "noAnswerCasesHaveAbstentionCondition": not missing_abstention_conditions,
        "documentFamiliesDoNotCrossSplits": not split_leakage,
        "reviewLedgerReferencesKnownCases": not unknown_reviewed_case_ids,
        "primaryCategoryLabelsValid": primary_categories_valid,
        "scenarioCategoryContextsValid": not invalid_category_contexts,
        "guardSuiteMinimumsMet": guard_suite_minimums_met,
    }
    structural_pass = all(structural_checks.values())
    reviewed_threshold = len(reviewed_core_ids) >= plan["preE0"]["minimumReviewedCasesBeforeComparison"]
    fully_reviewed = reviewed_core_ids == core_case_ids and human_review_status == "double_reviewed"
    ready_for_e0 = structural_pass and exact_split_counts and exact_category_counts \
        and exact_language_counts and fully_reviewed

    gaps = {
        split: core_targets[split] - split_counts[split]
        for split in CORE_SPLITS
        if core_targets[split] != split_counts[split]
    }
    hashes = generated_hashes(generated)
    provenance = provenance_hashes(root)
    generation_config = json.loads(
        (root / "fixtures" / "generation-config.json").read_text(encoding="utf-8")
    )
    result = {
        "schemaVersion": "material-rag-e0-readiness-v1",
        "status": "ready" if ready_for_e0 else "blocked",
        "readyForFormalComparison": structural_pass
        and len(core_cases) >= plan["preE0"]["minimumReviewedCasesBeforeComparison"]
        and reviewed_threshold,
        "readyForE0Freeze": ready_for_e0,
        "counts": {
            "allGeneratedCases": len(cases),
            "coreCases": len(core_cases),
            "coreBySplit": dict(sorted(split_counts.items())),
            "coreByLanguage": dict(sorted(language_counts.items())),
            "coreByPrimaryCategory": dict(sorted(category_counts.items())),
            "coreByModality": dict(sorted(modality_counts.items())),
            "anchors": len(anchors),
            "documents": len(documents),
            "reviewedCoreCases": len(reviewed_core_ids),
            "guardSuites": dict(sorted(guard_suite_counts.items())),
        },
        "targets": {
            "coreCases": core_targets,
            "primaryCategories": plan["primaryCategories"],
            "languageTargets": plan["languageTargets"],
            "guardSuites": plan["guardSuites"],
        },
        "gaps": {
            "coreCases": plan["preE0"]["requiredFrozenCoreCasesForBaseline"] - len(core_cases),
            "coreBySplit": gaps,
            "independentHumanReview": human_review_status,
            "reviewedCasesBeforeComparison": max(
                0, plan["preE0"]["minimumReviewedCasesBeforeComparison"] - len(reviewed_core_ids)),
            "primaryCategoryDelta": {
                category: target - category_counts[category]
                for category, target in plan["primaryCategories"].items()
                if target != category_counts[category]
            },
            "languageTargetDelta": {
                language: target - language_counts[language]
                for language, target in plan["languageTargets"].items()
                if target != language_counts[language]
            },
            "guardSuiteDelta": guard_suite_gaps,
        },
        "checks": structural_checks,
        "details": {
            "duplicateCaseIds": duplicate_values([case["caseId"] for case in cases]),
            "duplicateAnchorIds": duplicate_values([anchor["anchorId"] for anchor in anchors]),
            "unresolvedAnchors": unresolved_anchors,
            "sourceMismatches": source_mismatches,
            "metadataMismatches": metadata_mismatches,
            "invalidEvidenceGrades": invalid_evidence_grades,
            "invalidEvidenceShapes": invalid_evidence_shapes,
            "evidenceMetadataMismatches": evidence_metadata_mismatches,
            "evidenceGroupGoldMismatches": evidence_group_gold_mismatches,
            "unknownAllowedSourceVersions": unknown_allowed_source_versions,
            "invalidCategoryContexts": invalid_category_contexts,
            "missingExpectedAnswers": missing_answers,
            "missingAbstentionConditions": missing_abstention_conditions,
            "splitLeakage": split_leakage,
            "unknownReviewedCaseIds": unknown_reviewed_case_ids,
        },
    }
    lock = {
        "schemaVersion": "material-rag-corpus-lock-v1",
        "status": "frozen" if ready_for_e0 else "candidate",
        "hashAlgorithm": "sha256",
        "coreCaseCount": len(core_cases),
        "coreBySplit": dict(sorted(split_counts.items())),
        "independentHumanReview": human_review_status,
        "reviewedCoreCases": len(reviewed_core_ids),
        "reviewLedgerSha256": review_ledger_sha,
        "fixtureFontSha256": generation_config["fixtureFontSha256"],
        "provenanceFiles": provenance,
        "files": hashes,
    }
    return result, lock


def markdown_report(result: dict) -> str:
    counts = result["counts"]
    targets = result["targets"]
    gaps = result["gaps"]
    core_target = sum(targets["coreCases"].values())
    lines = [
        "# E0 readiness audit",
        "",
        f"Status: **{result['status'].upper()}**",
        "",
        "## Current corpus",
        "",
        f"- Core cases: {counts['coreCases']} / {core_target}",
        f"- Development: {counts['coreBySplit'].get('development', 0)} / {targets['coreCases']['development']}",
        f"- Validation: {counts['coreBySplit'].get('validation', 0)} / {targets['coreCases']['validation']}",
        f"- Holdout: {counts['coreBySplit'].get('holdout', 0)} / {targets['coreCases']['holdout']}",
        f"- Generated cases including guards: {counts['allGeneratedCases']}",
        f"- Anchors: {counts['anchors']}; documents: {counts['documents']}",
        "",
        "## Blocking gaps",
        "",
        f"- Missing core cases: {gaps['coreCases']}",
        f"- Split gaps: `{json.dumps(gaps['coreBySplit'], sort_keys=True)}`",
        f"- Independently reviewed core cases: {counts['reviewedCoreCases']}",
        f"- Independent human review status: `{gaps['independentHumanReview']}`",
        f"- Primary-category deltas: `{json.dumps(gaps['primaryCategoryDelta'], sort_keys=True)}`",
        f"- Language-target deltas: `{json.dumps(gaps['languageTargetDelta'], sort_keys=True)}`",
        f"- Guard-suite deltas: `{json.dumps(gaps['guardSuiteDelta'], sort_keys=True)}`",
        "",
        "## Structural checks",
        "",
    ]
    lines.extend(
        f"- {'PASS' if passed else 'FAIL'} - `{name}`"
        for name, passed in result["checks"].items()
    )
    lines.append("")
    if result["readyForE0Freeze"]:
        lines.extend([
            "The corpus lock is frozen: core targets, guard-suite minimums, structural checks and",
            "independent review all pass. Validation comparisons may proceed; Holdout remains sealed.",
            "",
        ])
    else:
        lines.extend([
            "The corpus lock is still a candidate. Do not run formal comparisons until all listed",
            "count, label, guard-suite and independent-review gaps are closed.",
            "",
        ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--review-ledger", type=Path)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--markdown-out", type=Path)
    parser.add_argument("--lock", "--candidate-lock", dest="lock", type=Path)
    args = parser.parse_args()
    review_ledger = args.review_ledger.resolve() if args.review_ledger else None
    result, lock = audit(args.root.resolve(), review_ledger)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    print(output, end="")
    for path, content in [
        (args.json_out, output),
        (args.markdown_out, markdown_report(result)),
        (args.lock, json.dumps(lock, ensure_ascii=False, indent=2) + "\n"),
    ]:
        if path:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    main()
