#!/usr/bin/env python3
"""Run an AI-assisted first-pass review over every core case and emit a ledger.

This performs a genuine per-case check (query answerability, gold/anchor
integrity, category/label consistency, abstention consistency and scenario
evaluationContext completeness) and records the outcome with an honest reviewer
identity. It intentionally does NOT fabricate a second independent human
reviewer: the auditor only counts a case as review-complete when TWO distinct
reviewers agree, so the double-review gate stays under human control. Pass
``--human-reviewer NAME`` to append a real second reviewer once a person has
actually confirmed the AI pass.
"""

from __future__ import annotations

import argparse
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
    ap.add_argument("--human-reviewer", default=None,
                    help="Real second reviewer id; add ONLY after a person confirms the AI pass.")
    args = ap.parse_args()

    cases = [json.loads(l) for l in (args.generated / "cases.jsonl").read_text().splitlines() if l.strip()]
    anchors = json.loads((args.generated / "ground-truth.json").read_text())["anchors"]
    anchor_ids = {a["anchorId"] for a in anchors}
    core = [c for c in cases if c["split"] in CORE]

    reviewers = [args.ai_reviewer] + ([args.human_reviewer] if args.human_reviewer else [])
    entries = []
    verdict_counts: Counter = Counter()
    for case in core:
        verdict, issues = review_case(case, anchor_ids)
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
        "schemaVersion": "material-rag-review-ledger-v1",
        "reviewPolicy": ("AI-assisted first pass by {}. The audit double-review gate requires a "
                         "second, independent HUMAN reviewer; add them with --human-reviewer only "
                         "after they actually confirm.").format(args.ai_reviewer),
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
