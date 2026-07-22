#!/usr/bin/env python3
"""Execute deterministic fixture contracts for the material-RAG guard suites."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SUITES = {
    "authorization": "guard_authorization",
    "versioning": "guard_versioning",
    "abstention": "guard_abstention",
    "visualAndOcr": "guard_visual_ocr",
    "failureAndRecovery": "guard_failure",
    "chartbookNarrowing": "guard_chartbook_scope",
    "regression": "guard_regression",
}


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def case_errors(case: dict, anchors: dict[str, dict], source_versions: set[str]) -> list[str]:
    """Run suite-aware contracts without pretending to execute online authorization or generation."""
    errors: list[str] = []
    split = case["split"]
    allowed = set(case.get("allowedSourceVersions", []))
    if not allowed or not allowed <= source_versions:
        errors.append("allowed source version is missing or unknown")
    if case.get("answerable"):
        if not case.get("expectedAnswer"):
            errors.append("answerable case has no expected answer")
        for anchor_id in case.get("goldAnchorIds", []):
            anchor = anchors.get(anchor_id)
            if anchor is None:
                errors.append(f"unknown gold anchor: {anchor_id}")
            elif anchor.get("split") != split:
                errors.append(f"gold anchor crosses suite: {anchor_id}")
    elif case.get("goldAnchorIds") or not case.get("abstentionCondition"):
        errors.append("no-answer case carries gold evidence or lacks an abstention condition")

    context = case.get("evaluationContext", {})
    primary = case.get("primaryCategory")
    if split == "guard_authorization" and primary == "versionAndAuthorization":
        if context.get("scenarioType") not in {"authorizationScope", "chartbookNarrowing"}:
            errors.append("authorization case lacks an authorization decision context")
    if split == "guard_versioning" and primary == "versionAndAuthorization":
        scenario = str(context.get("scenarioType", ""))
        if not (scenario.startswith("version") or scenario == "draftRejected"):
            errors.append("versioning case lacks a version decision context")
    if split == "guard_chartbook_scope":
        if context.get("scenarioType") != "chartbookNarrowing":
            errors.append("chartbook case lacks narrowing context")
    if split == "guard_failure" and primary == "failure":
        required = {"injectedDependency", "injectedState", "expectedSystemBehavior",
                    "forbiddenSystemBehavior"}
        if not required <= {key for key, value in context.items() if str(value).strip()}:
            errors.append("failure case lacks executable degraded-state context")
    if split == "guard_visual_ocr" and case.get("answerable"):
        if primary not in {"visual", "ocr"}:
            errors.append("visual/OCR answerable case has the wrong primary category")
    return errors


def execute(root: Path) -> dict:
    generated = root / "fixtures/generated"
    plan = json.loads((root / "experiment-plan-v2.json").read_text(encoding="utf-8"))
    cases = read_jsonl(generated / "cases.jsonl")
    ground_truth = json.loads((generated / "ground-truth.json").read_text(encoding="utf-8"))
    manifest = json.loads((generated / "corpus-manifest.json").read_text(encoding="utf-8"))
    anchors = {anchor["anchorId"]: anchor for anchor in ground_truth["anchors"]}
    source_versions = {
        f"{document['source']}:{document['version']}" for document in manifest["documents"]
    }
    targets = plan["guardSuites"]
    suite_results: dict[str, dict] = {}
    all_case_results: list[dict] = []
    for suite, split in SUITES.items():
        suite_cases = [case for case in cases if case.get("split") == split]
        case_results = []
        for case in suite_cases:
            errors = case_errors(case, anchors, source_versions)
            value = {"caseId": case["caseId"], "status": "passed" if not errors else "failed",
                     "errors": errors}
            case_results.append(value)
            all_case_results.append({"suite": suite, **value})
        target = targets.get(suite)
        enough_cases = target is None or len(suite_cases) >= target
        failed = sum(value["status"] == "failed" for value in case_results)
        suite_results[suite] = {
            "split": split,
            "target": target,
            "caseCount": len(suite_cases),
            "passedCases": len(suite_cases) - failed,
            "failedCases": failed,
            "minimumMet": enough_cases,
            "status": "passed" if enough_cases and failed == 0 else "failed",
        }
    statuses = Counter(value["status"] for value in suite_results.values())
    return {
        "schemaVersion": "material-rag-guard-contract-run-v1",
        "executionLevel": "fixture-contract",
        "status": "passed" if statuses["failed"] == 0 else "failed",
        "limitations": [
            "This run validates fixture decisions, grounding links and suite minimums.",
            "Online retrieval, authorization services, degraded dependencies and answer generation "
            "must be exercised by their later pipeline stages.",
        ],
        "suites": suite_results,
        "caseResults": all_case_results,
    }


def markdown_report(result: dict) -> str:
    lines = [
        "# Guard suite fixture-contract run",
        "",
        f"Status: **{result['status'].upper()}**",
        "",
        "This executes the corpus-level guard contracts. It does not claim an end-to-end pass for",
        "online authorization, dependency injection, retrieval or answer generation.",
        "",
        "| Suite | Cases | Minimum | Passed | Failed | Status |",
        "|---|---:|---:|---:|---:|:---:|",
    ]
    for suite, value in result["suites"].items():
        target = "—" if value["target"] is None else str(value["target"])
        lines.append(
            f"| {suite} | {value['caseCount']} | {target} | {value['passedCases']} | "
            f"{value['failedCases']} | {value['status'].upper()} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--markdown-out", type=Path)
    args = parser.parse_args()
    result = execute(args.root.resolve())
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    print(output, end="")
    for path, content in ((args.json_out, output),
                          (args.markdown_out, markdown_report(result))):
        if path:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
    raise SystemExit(0 if result["status"] == "passed" else 1)


if __name__ == "__main__":
    main()
