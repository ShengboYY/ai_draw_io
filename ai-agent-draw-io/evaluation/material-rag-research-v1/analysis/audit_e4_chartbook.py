#!/usr/bin/env python3
"""Audit E4 chartbook cases for genuine multi-source and scope-safe construction."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


def audit_chartbook_cases(root: Path) -> dict:
    generated = root / "fixtures/generated"
    payload = json.loads((generated / "e4-chartbook-cases.json").read_text(encoding="utf-8"))
    ground_truth = json.loads((generated / "ground-truth.json").read_text(encoding="utf-8"))
    manifest = json.loads((generated / "corpus-manifest.json").read_text(encoding="utf-8"))
    anchor_by_id = {anchor["anchorId"]: anchor for anchor in ground_truth["anchors"]}
    source_split = {
        f"{document['source']}:{document['version']}": document["split"]
        for document in manifest["documents"]
    }
    chartbook_by_id = {value["chartbookId"]: value for value in payload["chartbooks"]}
    invalid = []
    case_ids = [case["caseId"] for case in payload["cases"]]
    duplicate_case_ids = sorted(case_id for case_id, count in Counter(case_ids).items() if count > 1)
    checks = {
        "uniqueCaseIds": not duplicate_case_ids,
        "everyCaseHasMultipleGoldSources": True,
        "mountedAndUnmountedAreDisjoint": True,
        "caseScopeMatchesChartbook": True,
        "allGoldSourcesAreMounted": True,
        "allSourcesStayWithinSplit": True,
        "allGoldAnchorsResolve": True,
        "evidenceGroupsMatchGold": True,
    }
    for case in payload["cases"]:
        chartbook = chartbook_by_id.get(case["chartbookId"])
        mounted = set(case["mountedSourceVersions"])
        unmounted = set(case["unmountedSourceVersions"])
        gold_sources = set(case["goldSourceVersions"])
        anchors = [anchor_by_id.get(anchor_id) for anchor_id in case["goldAnchorIds"]]
        evidence_ids = {
            requirement["anchorId"]
            for group in case["requiredEvidenceGroups"]
            for requirement in group["evidence"]
        }
        case_errors = []
        if chartbook is None or case["split"] != chartbook.get("split"):
            case_errors.append("unknown chartbook or split mismatch")
        elif (case["mountedSourceVersions"] != chartbook["mountedSourceVersions"]
              or case["unmountedSourceVersions"] != chartbook["unmountedSourceVersions"]):
            checks["caseScopeMatchesChartbook"] = False
            case_errors.append("mounted or unmounted scope differs from chartbook")
        if len(gold_sources) < 2:
            checks["everyCaseHasMultipleGoldSources"] = False
            case_errors.append("fewer than two gold sources")
        if mounted & unmounted:
            checks["mountedAndUnmountedAreDisjoint"] = False
            case_errors.append("mounted and unmounted sources overlap")
        if not gold_sources.issubset(mounted):
            checks["allGoldSourcesAreMounted"] = False
            case_errors.append("gold source is not mounted")
        if any(source_split.get(source) != case["split"] for source in mounted | unmounted):
            checks["allSourcesStayWithinSplit"] = False
            case_errors.append("source crosses the case split")
        if any(anchor is None for anchor in anchors):
            checks["allGoldAnchorsResolve"] = False
            case_errors.append("unknown gold anchor")
        else:
            resolved_sources = {f"{anchor['source']}:{anchor['version']}" for anchor in anchors}
            if resolved_sources != gold_sources:
                checks["allGoldAnchorsResolve"] = False
                case_errors.append("gold-source metadata differs from anchors")
        if evidence_ids != set(case["goldAnchorIds"]):
            checks["evidenceGroupsMatchGold"] = False
            case_errors.append("required evidence differs from gold anchors")
        if case_errors:
            invalid.append({"caseId": case["caseId"], "errors": case_errors})
    counts = Counter(case["split"] for case in payload["cases"])
    return {
        "schemaVersion": "material-rag-e4-chartbook-audit-v1",
        "status": "ready" if all(checks.values()) and not invalid else "not_ready",
        "caseCounts": dict(sorted(counts.items())),
        "chartbookCount": len(payload["chartbooks"]),
        "invalidCaseCount": len(invalid),
        "checks": checks,
        "details": {"duplicateCaseIds": duplicate_case_ids, "invalidCases": invalid},
    }


def main() -> None:
    """Emit a reviewable E4b fixture audit without changing the core-corpus audit."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    result = audit_chartbook_cases(args.root.resolve())
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    print(output, end="")
    if args.json_out:
        args.json_out.write_text(output, encoding="utf-8")


if __name__ == "__main__":
    main()
