#!/usr/bin/env python3
"""Compare raw and source-aware context bundles from an already frozen top-40."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path


def select(candidates: list[dict], limit: int = 8) -> list[dict]:
    """Keep rank order, drop duplicate chunks, then give each source one early slot."""
    unique = []
    seen_chunks = set()
    for candidate in candidates:
        if candidate["chunkId"] not in seen_chunks:
            unique.append(candidate)
            seen_chunks.add(candidate["chunkId"])
    selected, seen_sources = [], set()
    for candidate in unique:
        if candidate["sourceVersion"] not in seen_sources:
            selected.append(candidate)
            seen_sources.add(candidate["sourceVersion"])
        if len(selected) == limit:
            return selected
    for candidate in unique:
        if candidate not in selected:
            selected.append(candidate)
        if len(selected) == limit:
            break
    return selected


def gold_evidence_recall(case: dict, context: list[dict]) -> float:
    """Measure anchor-level recall using only the retrieval run's frozen gold chunk map."""
    groups = case.get("fixedGoldChunkIdsByAnchor", {})
    if not groups:
        return 0.0
    chunk_ids = {candidate["chunkId"] for candidate in context}
    return sum(bool(chunk_ids.intersection(group)) for group in groups.values()) / len(groups)


def paired_bootstrap_interval(differences: list[float], samples: int = 10_000) -> list[float]:
    """Return a deterministic paired-bootstrap 95% interval for the mean difference."""
    if not differences:
        return [0.0, 0.0]
    generator = random.Random(20260722)
    count = len(differences)
    means = sorted(
        sum(differences[generator.randrange(count)] for _ in range(count)) / count
        for _ in range(samples)
    )
    return [means[int(0.025 * samples)], means[int(0.975 * samples) - 1]]


def compare(raw: dict, limit: int = 8, minimum_change_rate: float = 0.2) -> dict:
    """Emit both arms and fail the effectiveness gate when the selector changes too few cases."""
    bundles = []
    for case in raw["metrics"]["caseResults"]:
        control = case["candidates"][:limit]
        candidate = select(case["candidates"], limit)
        control_ids = [value["chunkId"] for value in control]
        candidate_ids = [value["chunkId"] for value in candidate]
        bundles.append({
            "caseId": case["caseId"],
            "changed": control_ids != candidate_ids,
            "controlContext": control,
            "candidateContext": candidate,
            "controlGoldEvidenceRecall": gold_evidence_recall(case, control),
            "candidateGoldEvidenceRecall": gold_evidence_recall(case, candidate),
            "controlSourceCount": len({value["sourceVersion"] for value in control}),
            "candidateSourceCount": len({value["sourceVersion"] for value in candidate}),
            "language": case.get("language"),
            "primaryCategory": case.get("primaryCategory"),
        })
    count = len(bundles)
    changed = sum(bundle["changed"] for bundle in bundles)

    def mean(field: str) -> float:
        return sum(bundle[field] for bundle in bundles) / count if count else 0.0

    change_rate = changed / count if count else 0.0
    recall_differences = [
        bundle["candidateGoldEvidenceRecall"] - bundle["controlGoldEvidenceRecall"]
        for bundle in bundles
    ]
    source_differences = [
        bundle["candidateSourceCount"] - bundle["controlSourceCount"] for bundle in bundles
    ]
    slices = {}
    for field in ("language", "primaryCategory"):
        for value in sorted({bundle[field] for bundle in bundles if bundle.get(field)}):
            members = [bundle for bundle in bundles if bundle.get(field) == value]
            key = f"{field}:{value}"
            slices[key] = {
                "count": len(members),
                "controlMeanGoldEvidenceRecall": sum(
                    member["controlGoldEvidenceRecall"] for member in members
                ) / len(members),
                "candidateMeanGoldEvidenceRecall": sum(
                    member["candidateGoldEvidenceRecall"] for member in members
                ) / len(members),
            }
    return {
        "schemaVersion": "material-rag-e6-context-selection-v2",
        "selector": "source-aware-top8-v1",
        "limit": limit,
        "caseCount": count,
        "changedCaseCount": changed,
        "changedCaseRate": change_rate,
        "minimumChangeRate": minimum_change_rate,
        "effectiveExperiment": change_rate >= minimum_change_rate,
        "metrics": {
            "controlMeanGoldEvidenceRecall": mean("controlGoldEvidenceRecall"),
            "candidateMeanGoldEvidenceRecall": mean("candidateGoldEvidenceRecall"),
            "controlMeanSourceCount": mean("controlSourceCount"),
            "candidateMeanSourceCount": mean("candidateSourceCount"),
            "pairedGoldEvidenceRecallDelta95CI": paired_bootstrap_interval(recall_differences),
            "pairedSourceCountDelta95CI": paired_bootstrap_interval(source_differences),
        },
        "slices": slices,
        "bundles": bundles,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw", type=Path)
    parser.add_argument("--json-out", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=8)
    parser.add_argument("--minimum-change-rate", type=float, default=0.2)
    args = parser.parse_args()
    raw = json.loads(args.raw.read_text())
    result = compare(raw, args.limit, args.minimum_change_rate)
    result["inputRaw"] = {
        "path": args.raw.as_posix(),
        "sha256": hashlib.sha256(args.raw.read_bytes()).hexdigest(),
        "runId": raw.get("runId"),
        "gitCommit": raw.get("gitCommit"),
        "corpusLockSha256": raw.get("corpusLockSha256"),
        "split": raw.get("split"),
    }
    result["selectorScriptSha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    args.json_out.write_text(json.dumps(result, indent=2) + "\n")
    if not result["effectiveExperiment"]:
        raise SystemExit("selector effectiveness gate failed")


if __name__ == "__main__":
    main()
