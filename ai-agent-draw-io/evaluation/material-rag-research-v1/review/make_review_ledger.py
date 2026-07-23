#!/usr/bin/env python3
"""Run an AI-assisted first-pass review over every core case and emit a ledger.

This performs a genuine per-case check (query answerability, gold/anchor
integrity, category/label consistency, abstention consistency and scenario
evaluationContext completeness) and records the outcome with an honest reviewer
identity. It intentionally does NOT fabricate a second independent human
reviewer: the auditor only counts a case as review-complete when TWO distinct
reviewers agree, so the double-review gate stays under human control. Pass two
complete ``--human-review PATH`` artifacts after independent people have
actually reviewed every frozen core case.
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
    ap.add_argument("--ai-reviewer", default="claude-opus-4.8-review")
    ap.add_argument("--human-review", type=Path, action="append", default=[],
                    help="Complete material-rag-human-review-v1 artifact; repeat per human reviewer.")
    args = ap.parse_args()

    cases = [json.loads(l) for l in (args.generated / "cases.jsonl").read_text().splitlines() if l.strip()]
    anchors = json.loads((args.generated / "ground-truth.json").read_text())["anchors"]
    anchor_ids = {a["anchorId"] for a in anchors}
    core = [c for c in cases if c["split"] in CORE]

    expected_case_ids = {case["caseId"] for case in core}
    human_reviews = [load_human_review(path.resolve(), expected_case_ids)
                     for path in args.human_review]
    human_reviewers = [review["reviewerId"] for review in human_reviews]
    if len(set(human_reviewers)) != len(human_reviewers):
        ap.error("human review artifacts must have distinct reviewer IDs")
    if args.ai_reviewer in human_reviewers:
        ap.error("AI and human reviewer IDs must be distinct")
    ledger_root = args.out.resolve().parent
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
            "notes": "AI first-pass checks passed" if not issues else "; ".join(issues),
        })

    ledger = {
        "schemaVersion": "material-rag-review-ledger-v2",
        "reviewPolicy": ("AI-assisted first pass by {}. The audit double-review gate requires a "
                         "pair of complete, independent HUMAN per-case review artifacts; a reviewer "
                         "name alone never qualifies.").format(args.ai_reviewer),
        "reviewerRegistry": {
            args.ai_reviewer: {"kind": "ai"},
            **{reviewer: {"kind": "human"} for reviewer in human_reviewers},
        },
        "humanReviewArtifacts": [
            {"reviewerId": review["reviewerId"], **review["artifact"]}
            for review in human_reviews
        ],
        "reviewerCount": len(reviewers),
        "coreCasesReviewed": len(entries),
        "verdicts": dict(verdict_counts),
        "cases": entries,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"reviewed {len(entries)} core cases; verdicts={dict(verdict_counts)}; "
          f"reviewers={reviewers}")


if __name__ == "__main__":
    main()
