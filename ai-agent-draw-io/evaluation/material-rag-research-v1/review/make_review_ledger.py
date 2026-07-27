#!/usr/bin/env python3
"""Run deterministic contract review over every core case and emit a ledger.

This performs a genuine per-case check (query answerability, gold/anchor
integrity, category/label consistency, abstention consistency and scenario
evaluationContext completeness) and records the outcome with an honest reviewer
identity. It supports either two complete independent human artifacts or the
explicitly weaker automated-full plus project-owner representative spot-check
method. The latter never claims independent double-human review.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE = ("development", "validation", "holdout")
CONTEXT_FIELDS = {
    "failure": ("injectedDependency", "injectedState", "expectedSystemBehavior",
                "forbiddenSystemBehavior"),
    "versionAndAuthorization": ("scenarioType", "actingRole", "scopeConstraint", "expectedDecision"),
}


def file_sha256(path: Path) -> str:
    """Bind an imported human review to the exact per-case artifact."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_human_review(path: Path, expected_case_ids: set[str]) -> dict:
    """Load one complete independent human review; a reviewer name alone is insufficient."""
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("schemaVersion") != "material-rag-human-review-v1":
        raise ValueError(f"unsupported human review schema: {path}")
    reviewer_id = str(payload.get("reviewerId", "")).strip()
    if not reviewer_id:
        raise ValueError(f"human review requires reviewerId: {path}")
    decisions: dict[str, dict] = {}
    for decision in payload.get("cases", []):
        case_id = str(decision.get("caseId", "")).strip()
        verdict = decision.get("verdict")
        notes = str(decision.get("notes", "")).strip()
        if not case_id or case_id in decisions:
            raise ValueError(f"human review has missing or duplicate caseId: {path}")
        if verdict not in {"accept", "needs_fix"}:
            raise ValueError(f"human review has invalid verdict for {case_id}: {path}")
        if verdict == "needs_fix" and not notes:
            raise ValueError(f"human review needs_fix decision requires notes for {case_id}: {path}")
        decisions[case_id] = {"verdict": verdict, "notes": notes}
    if set(decisions) != expected_case_ids:
        raise ValueError(f"human review must cover every core case exactly once: {path}")
    return {
        "reviewerId": reviewer_id,
        "decisions": decisions,
        "artifact": {"path": path.as_posix(), "sha256": file_sha256(path)},
    }


def load_owner_spot_check(path: Path, policy_path: Path, known_case_ids: set[str],
                          known_task_ids: set[str]) -> dict:
    """Validate the project owner's explicit approval of the frozen representative sample."""
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    payload = json.loads(path.read_text(encoding="utf-8"))
    if policy.get("schemaVersion") != "material-rag-owner-spot-check-policy-v1" \
            or policy.get("reviewMethod") != "automated-full-owner-spot-check-v1":
        raise ValueError(f"unsupported owner spot-check policy: {policy_path}")
    if payload.get("schemaVersion") != "material-rag-owner-spot-check-v1" \
            or payload.get("decision") != "approve":
        raise ValueError(f"owner spot-check is not approved: {path}")
    reviewer_id = str(payload.get("reviewerId", "")).strip()
    if not reviewer_id or not str(payload.get("confirmationNote", "")).strip():
        raise ValueError(f"owner spot-check requires reviewerId and confirmationNote: {path}")
    required_core = set(policy.get("requiredCoreCaseIds", []))
    required_tasks = set(policy.get("requiredGenerationTaskIds", []))
    confirmed_core = set(payload.get("confirmedCoreCaseIds", []))
    confirmed_tasks = set(payload.get("confirmedGenerationTaskIds", []))
    if not required_core or not required_core.issubset(known_case_ids) \
            or not required_core.issubset(confirmed_core) \
            or not confirmed_core.issubset(known_case_ids):
        raise ValueError(f"owner spot-check does not cover the required core sample: {path}")
    if not required_tasks or not required_tasks.issubset(known_task_ids) \
            or not required_tasks.issubset(confirmed_tasks) \
            or not confirmed_tasks.issubset(known_task_ids):
        raise ValueError(f"owner spot-check does not cover the required generation sample: {path}")
    return {
        "reviewerId": reviewer_id,
        "reviewMethod": policy["reviewMethod"],
        "artifact": {"path": path.as_posix(), "sha256": file_sha256(path)},
        "policy": {"path": policy_path.as_posix(), "sha256": file_sha256(policy_path)},
    }


def review_case(case: dict, anchor_ids: set[str]) -> tuple[str, list[str]]:
    """Return (verdict, issues). verdict is 'agreed' when no issue is found."""
    issues: list[str] = []
    if not case.get("query", "").strip():
        issues.append("empty query")
    if case.get("answerable"):
        if not case.get("goldAnchorIds"):
            issues.append("answerable but no gold anchors")
        if not case.get("expectedAnswer"):
            issues.append("answerable but no expected answer")
        if not case.get("requiredEvidenceGroups"):
            issues.append("answerable but no evidence groups")
        for aid in case.get("goldAnchorIds", []):
            if aid not in anchor_ids:
                issues.append(f"gold anchor missing from ground truth: {aid}")
    else:
        if case.get("goldAnchorIds"):
            issues.append("no-answer case carries gold anchors")
        if not case.get("abstentionCondition"):
            issues.append("no-answer case lacks abstention condition")
    fields = CONTEXT_FIELDS.get(case.get("primaryCategory"))
    if fields:
        ctx = case.get("evaluationContext") or {}
        missing = [f for f in fields if not str(ctx.get(f, "")).strip()]
        if missing:
            issues.append(f"missing evaluationContext fields: {missing}")
    ql = "zh" if any("一" <= ch <= "鿿" for ch in case.get("query", "")) else "en"
    if case.get("queryLanguage") != ql:
        issues.append("queryLanguage tag inconsistent with query script")
    return ("agreed" if not issues else "needs_fix"), issues


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--generated", type=Path, default=ROOT / "fixtures" / "generated")
    ap.add_argument("--out", type=Path, default=ROOT / "review" / "review-ledger.json")
    ap.add_argument("--ai-reviewer", default="automated-contract-review-v1")
    ap.add_argument("--human-review", type=Path, action="append", default=[],
                    help="Complete material-rag-human-review-v1 artifact; repeat per human reviewer.")
    ap.add_argument("--owner-spot-check", type=Path,
                    help="Approved material-rag-owner-spot-check-v1 artifact.")
    ap.add_argument("--owner-spot-check-policy", type=Path,
                    default=ROOT / "review" / "owner-spot-check-policy-v1.json")
    args = ap.parse_args()

    cases = [json.loads(l) for l in (args.generated / "cases.jsonl").read_text().splitlines() if l.strip()]
    anchors = json.loads((args.generated / "ground-truth.json").read_text())["anchors"]
    anchor_ids = {a["anchorId"] for a in anchors}
    core = [c for c in cases if c["split"] in CORE]
    generation_tasks = json.loads(
        (ROOT / "fixtures" / "drawio-generation-tasks-v3.json").read_text(encoding="utf-8")
    )["tasks"]

    expected_case_ids = {case["caseId"] for case in core}
    known_task_ids = {task["taskId"] for task in generation_tasks}
    if args.owner_spot_check and args.human_review:
        ap.error("choose owner spot-check governance or complete two-human review, not both")
    owner_review = load_owner_spot_check(
        args.owner_spot_check.resolve(),
        args.owner_spot_check_policy.resolve(),
        expected_case_ids,
        known_task_ids,
    ) if args.owner_spot_check else None
    human_reviews = [load_human_review(path.resolve(), expected_case_ids)
                     for path in args.human_review]
    human_reviewers = [review["reviewerId"] for review in human_reviews]
    if len(set(human_reviewers)) != len(human_reviewers):
        ap.error("human review artifacts must have distinct reviewer IDs")
    if args.ai_reviewer in human_reviewers:
        ap.error("AI and human reviewer IDs must be distinct")
    ledger_root = args.out.resolve().parent
    for reference in (
            owner_review["artifact"], owner_review["policy"]
    ) if owner_review else ():
        reference_path = Path(reference["path"]).resolve()
        if not reference_path.is_relative_to(ledger_root):
            ap.error("owner spot-check artifacts must be stored beside or below the review ledger")
        reference["path"] = reference_path.relative_to(ledger_root).as_posix()
    for review in human_reviews:
        artifact_path = Path(review["artifact"]["path"]).resolve()
        if not artifact_path.is_relative_to(ledger_root):
            ap.error("human review artifacts must be stored beside or below the review ledger")
        review["artifact"]["path"] = artifact_path.relative_to(ledger_root).as_posix()
    reviewers = [args.ai_reviewer, *human_reviewers]
    entries = []
    verdict_counts: Counter = Counter()
    for case in core:
        verdict, issues = review_case(case, anchor_ids)
        human_issues = [
            f"{review['reviewerId']}: {review['decisions'][case['caseId']]['notes']}"
            for review in human_reviews
            if review["decisions"][case["caseId"]]["verdict"] == "needs_fix"
        ]
        issues.extend(human_issues)
        verdict = "needs_fix" if issues else "agreed"
        verdict_counts[verdict] += 1
        entries.append({
            "caseId": case["caseId"],
            "reviewers": reviewers,
            "status": "agreed" if verdict == "agreed" else "needs_fix",
            "primaryCategory": case["primaryCategory"],
            "split": case["split"],
            "notes": (
                "Automated contract checks passed" if owner_review
                else "AI first-pass checks passed"
            ) if not issues else "; ".join(issues),
        })

    ledger = {
        "schemaVersion": (
            "material-rag-review-ledger-v3" if owner_review
            else "material-rag-review-ledger-v2"
        ),
        "reviewPolicy": (
            "Automated full-case contract review plus an explicit project-owner spot-check "
            "of the frozen representative draw.io sample; no independent double-human claim."
            if owner_review else
            ("AI-assisted first pass by {}. The audit double-review gate requires a pair of "
             "complete, independent HUMAN per-case review artifacts; a reviewer name alone "
             "never qualifies.").format(args.ai_reviewer)
        ),
        "reviewerRegistry": {
            args.ai_reviewer: {"kind": "automated" if owner_review else "ai"},
            **({owner_review["reviewerId"]: {"kind": "human_project_owner"}}
               if owner_review else {}),
            **{reviewer: {"kind": "human"} for reviewer in human_reviewers},
        },
        "reviewerCount": len(reviewers) + (1 if owner_review else 0),
        "coreCasesReviewed": len(entries),
        "verdicts": dict(verdict_counts),
        "cases": entries,
    }
    if owner_review:
        ledger["reviewMethod"] = owner_review["reviewMethod"]
        ledger["ownerSpotCheckArtifact"] = {
            "reviewerId": owner_review["reviewerId"],
            "path": owner_review["artifact"]["path"],
            "sha256": owner_review["artifact"]["sha256"],
            "policyPath": owner_review["policy"]["path"],
            "policySha256": owner_review["policy"]["sha256"],
        }
    else:
        ledger["humanReviewArtifacts"] = [
            {"reviewerId": review["reviewerId"], **review["artifact"]}
            for review in human_reviews
        ]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    participants = [*reviewers, *([owner_review["reviewerId"]] if owner_review else [])]
    print(f"reviewed {len(entries)} core cases; verdicts={dict(verdict_counts)}; "
          f"participants={participants}")


if __name__ == "__main__":
    main()
