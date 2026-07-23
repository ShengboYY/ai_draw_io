#!/usr/bin/env python3
"""Fail-closed structural audit for the post-R23 Stage A decision cohort."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "fixtures" / "stage-a-evidence-decision-cohort-v1.json"
MANIFEST = ROOT / "fixtures" / "generated" / "corpus-manifest.json"
GENERATION_TASKS = ROOT / "fixtures" / "drawio-generation-tasks-v3.json"

EXPECTED_DISTRIBUTION = {
    "Ready": 12,
    "InsufficientEvidence": 6,
    "ClarificationNeeded": 4,
    "DegradedDependency": 4,
    "NotRequired": 4,
}
BLOCKED_OUTCOMES = {"InsufficientEvidence", "ClarificationNeeded", "DegradedDependency"}
ALLOWED_RETRIEVAL = {"completed", "attempted", "none"}
ALLOWED_MUTATION = {"allowed", "blocked"}
ALLOWED_LANGUAGES = {"en", "zh", "crossLanguage"}


def forbidden_families(manifest: dict, generation_fixture: dict) -> set[str]:
    """Reserve every existing corpus family and active generation source for non-overlap."""
    families = {
        document["documentFamily"]
        for document in manifest.get("documents", [])
        if document.get("documentFamily")
    }
    families.update(
        task["sourceVersion"].split(":", 1)[0]
        for task in generation_fixture.get("tasks", [])
        if task.get("sourceVersion")
    )
    return families


def cohort_sha256(cohort: dict) -> str:
    payload = json.dumps(cohort, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def independent_review_errors(cohort: dict, review: dict | None) -> list[str]:
    if review is None:
        return ["independent review ledger is missing"]
    errors = []
    if review.get("schemaVersion") != "material-rag-stage-a-independent-review-v1":
        errors.append("independent review ledger schema is invalid")
    if review.get("cohortId") != cohort.get("cohortId"):
        errors.append("independent review ledger cohortId does not match")
    if review.get("cohortSha256") != cohort_sha256(cohort):
        errors.append("independent review ledger cohort hash does not match")
    reviewer = review.get("reviewer", {})
    if not reviewer.get("reviewerId") or reviewer.get("reviewerId") == cohort.get("authorId"):
        errors.append("reviewer must be identified and independent from the cohort author")
    if reviewer.get("kind") not in {"human", "independent_ai"}:
        errors.append("reviewer kind must be human or independent_ai")
    decisions = review.get("caseDecisions")
    if not isinstance(decisions, list):
        errors.append("caseDecisions must be a list")
        return errors
    expected_ids = {case.get("caseId") for case in cohort.get("cases", [])}
    reviewed_ids = {decision.get("caseId") for decision in decisions}
    if reviewed_ids != expected_ids or len(decisions) != len(expected_ids):
        errors.append("review ledger must contain exactly one decision for every cohort case")
    if any(decision.get("decision") != "approve" for decision in decisions):
        errors.append("every case decision must be approve before freezing")
    return errors


def audit_cohort(cohort: dict, reserved_families: set[str],
                 review: dict | None = None) -> dict:
    errors: list[str] = []
    cases = cohort.get("cases")
    if cohort.get("schemaVersion") != "material-rag-stage-a-evidence-decision-cohort-v1":
        errors.append("schemaVersion must be material-rag-stage-a-evidence-decision-cohort-v1")
    if not isinstance(cases, list):
        return {"status": "blocked", "structuralReady": False, "errors": ["cases must be a list"]}
    if len(cases) != 30:
        errors.append(f"case count must be 30, got {len(cases)}")

    ids = [case.get("caseId") for case in cases]
    duplicate_ids = sorted(case_id for case_id, count in Counter(ids).items() if count > 1)
    if duplicate_ids:
        errors.append(f"duplicate case ids: {duplicate_ids}")

    distribution = Counter(case.get("expectedOutcome") for case in cases)
    if dict(distribution) != EXPECTED_DISTRIBUTION:
        errors.append(f"outcome distribution mismatch: {dict(distribution)}")
    if cohort.get("requiredDistribution") != EXPECTED_DISTRIBUTION:
        errors.append("requiredDistribution does not match the preregistered distribution")

    blocked = 0
    ready_visual = 0
    families: set[str] = set()
    for case in cases:
        case_id = case.get("caseId", "<missing>")
        required = ("language", "operation", "request", "scenario", "expectedOutcome",
                    "expectedRetrieval", "expectedCanvasMutation", "requiresVisualArtifact")
        missing = [field for field in required if field not in case]
        if missing:
            errors.append(f"{case_id}: missing fields {missing}")
            continue
        if case["language"] not in ALLOWED_LANGUAGES:
            errors.append(f"{case_id}: invalid language {case['language']}")
        if not str(case["operation"]).strip() or not str(case["request"]).strip():
            errors.append(f"{case_id}: operation and request must be non-empty")
        if case["expectedRetrieval"] not in ALLOWED_RETRIEVAL:
            errors.append(f"{case_id}: invalid expectedRetrieval")
        if case["expectedCanvasMutation"] not in ALLOWED_MUTATION:
            errors.append(f"{case_id}: invalid expectedCanvasMutation")

        outcome = case["expectedOutcome"]
        mutation = case["expectedCanvasMutation"]
        retrieval = case["expectedRetrieval"]
        family = case.get("documentFamily")
        if family:
            families.add(family)
            if family in reserved_families:
                errors.append(f"{case_id}: document family overlaps an existing corpus: {family}")

        if outcome in BLOCKED_OUTCOMES:
            blocked += 1
            if mutation != "blocked":
                errors.append(f"{case_id}: blocked outcome must block canvas mutation")
        elif mutation != "allowed":
            errors.append(f"{case_id}: Ready/NotRequired must declare allowed mutation")

        if outcome == "NotRequired":
            if retrieval != "none" or family is not None:
                errors.append(f"{case_id}: NotRequired must have no retrieval and no document family")
        else:
            if family is None:
                errors.append(f"{case_id}: material-backed outcome requires a document family")

        if outcome == "ClarificationNeeded" and retrieval != "none":
            errors.append(f"{case_id}: clarification must stop before material retrieval")
        if outcome == "Ready" and retrieval != "completed":
            errors.append(f"{case_id}: Ready requires completed retrieval")
        if outcome == "DegradedDependency" and retrieval != "attempted":
            errors.append(f"{case_id}: degraded dependency requires attempted retrieval")
        if outcome == "Ready" and case["requiresVisualArtifact"]:
            ready_visual += 1

    if blocked != 14:
        errors.append(f"blocked-case count must be 14, got {blocked}")
    if ready_visual < 2:
        errors.append("Ready cases must include at least two visual/OCR artifact cases")
    if len(families) < 10:
        errors.append(f"material-backed cases must span at least 10 new families, got {len(families)}")

    structurally_ready = not errors
    review_errors = independent_review_errors(cohort, review)
    independently_reviewed = (
        cohort.get("status") == "frozen_independently_reviewed" and not review_errors
    )
    return {
        "schemaVersion": "material-rag-stage-a-cohort-audit-v1",
        "status": "ready" if structurally_ready and independently_reviewed else
                  "review_pending" if structurally_ready else "blocked",
        "structuralReady": structurally_ready,
        "independentReviewComplete": independently_reviewed,
        "counts": {
            "cases": len(cases),
            "blockedCases": blocked,
            "newDocumentFamilies": len(families),
            "readyVisualArtifactCases": ready_visual,
            "outcomes": dict(distribution),
        },
        "errors": errors,
        "reviewErrors": review_errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=Path, default=FIXTURE)
    parser.add_argument("--review-ledger", type=Path)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    cohort = json.loads(args.fixture.read_text(encoding="utf-8"))
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    generation = json.loads(GENERATION_TASKS.read_text(encoding="utf-8"))
    review = json.loads(args.review_ledger.read_text(encoding="utf-8")) if args.review_ledger else None
    result = audit_cohort(cohort, forbidden_families(manifest, generation), review)
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if result["structuralReady"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
