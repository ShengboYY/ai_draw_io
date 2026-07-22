#!/usr/bin/env python3
"""Compare two paired dense-retrieval runs and report confidence intervals."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
from pathlib import Path


METRICS = ("recallAt1", "recallAt5", "recallAt10", "recallAt40", "mrrAt10")


def wilson(successes: int, count: int) -> list[float]:
    """Return a 95% Wilson interval for a binomial proportion."""
    if count == 0:
        return [0.0, 0.0]
    z = 1.959963984540054
    proportion = successes / count
    denominator = 1 + z * z / count
    centre = (proportion + z * z / (2 * count)) / denominator
    margin = z * math.sqrt(
        proportion * (1 - proportion) / count + z * z / (4 * count * count)
    ) / denominator
    return [max(0.0, centre - margin), min(1.0, centre + margin)]


def percentile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    index = (len(ordered) - 1) * probability
    lower = math.floor(index)
    upper = math.ceil(index)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (index - lower)


def bootstrap_mean(values: list[float], seed: int, samples: int = 10_000) -> list[float]:
    """Return a deterministic percentile-bootstrap 95% interval for a mean."""
    randomizer = random.Random(seed)
    count = len(values)
    means = [sum(values[randomizer.randrange(count)] for _ in range(count)) / count
             for _ in range(samples)]
    return [percentile(means, 0.025), percentile(means, 0.975)]


def case_value(rank: int, metric: str) -> float:
    if metric == "mrrAt10":
        return 1 / rank if 1 <= rank <= 10 else 0.0
    cutoff = int(metric.removeprefix("recallAt"))
    return 1.0 if 1 <= rank <= cutoff else 0.0


def interval(values: list[float], metric: str, seed: int) -> list[float]:
    if metric.startswith("recall"):
        return wilson(int(sum(values)), len(values))
    return bootstrap_mean(values, seed)


def slices(case: dict) -> list[str]:
    return [
        f"language:{case['language']}",
        f"category:{case['category']}",
        f"primaryCategory:{case['primaryCategory']}",
    ]


def postprocess_activation(e0_cases: dict[str, dict], e1_cases: dict[str, dict],
                           case_ids: list[str]) -> dict:
    """Measure postprocessing changes, including rank-only diversification."""
    changed_case_ids = []
    baseline_positions = 0
    candidate_positions = 0
    removed_positions = 0
    top10_removed_positions = 0
    for case_id in case_ids:
        baseline = [candidate["chunkId"] for candidate in e0_cases[case_id]["candidates"]]
        candidate_order = [item["chunkId"] for item in e1_cases[case_id]["candidates"]]
        candidate = set(candidate_order)
        removed = [chunk_id for chunk_id in baseline if chunk_id not in candidate]
        # A source cap normally reorders the same top-40 candidates, so removal alone
        # cannot represent whether this postprocessor activated.
        if baseline != candidate_order:
            changed_case_ids.append(case_id)
        baseline_positions += len(baseline)
        candidate_positions += len(e1_cases[case_id]["candidates"])
        removed_positions += len(removed)
        top10_removed_positions += sum(chunk_id not in candidate for chunk_id in baseline[:10])
    return {
        "changedCases": len(changed_case_ids),
        "changedCaseIds": changed_case_ids,
        "baselinePositions": baseline_positions,
        "candidatePositions": candidate_positions,
        "removedBaselinePositions": removed_positions,
        "replacementRate": removed_positions / baseline_positions if baseline_positions else 0.0,
        "top10RemovedBaselinePositions": top10_removed_positions,
    }


def source_diversity(cases: dict[str, dict], case_ids: list[str]) -> dict:
    """Summarize mounted-source coverage, concentration, gold coverage and leakage."""
    mounted_coverage = []
    max_source_share = []
    unique_sources = []
    gold_at10 = []
    gold_at40 = []
    leakage_positions = 0
    for case_id in case_ids:
        case = cases[case_id]
        mounted = set(case.get("mountedSourceVersions", []))
        unmounted = set(case.get("unmountedSourceVersions", []))
        gold = set(case.get("goldSourceVersions", []))
        top40 = [candidate.get("sourceVersion") for candidate in case["candidates"][:40]]
        top10 = top40[:10]
        top10_sources = set(top10)
        if mounted:
            mounted_coverage.append(len(top10_sources & mounted) / len(mounted))
            leakage_positions += sum(source not in mounted for source in top40)
        else:
            leakage_positions += sum(source in unmounted for source in top40)
        unique_sources.append(len(top10_sources))
        counts = {source: top10.count(source) for source in top10_sources}
        max_source_share.append(max(counts.values()) / len(top10) if top10 else 0.0)
        if gold:
            gold_at10.append(1.0 if gold.issubset(top10_sources) else 0.0)
            gold_at40.append(1.0 if gold.issubset(set(top40)) else 0.0)
    return {
        "caseCount": len(case_ids),
        "goldSourceCaseCount": len(gold_at10),
        "meanMountedCoverageAt10": sum(mounted_coverage) / len(mounted_coverage)
        if mounted_coverage else 0.0,
        "meanUniqueSourcesAt10": sum(unique_sources) / len(unique_sources),
        "meanMaxSourceShareAt10": sum(max_source_share) / len(max_source_share),
        "goldSourceRecallAt10": sum(gold_at10) / len(gold_at10) if gold_at10 else 0.0,
        "goldSourceRecallAt40": sum(gold_at40) / len(gold_at40) if gold_at40 else 0.0,
        "unmountedLeakagePositions": leakage_positions,
    }


def compare(e0: dict, e1: dict, variable_field: str = "canonicalMode") -> dict:
    """Compare paired case ranks after rejecting any experiment-control drift."""
    supported_variables = {"canonicalMode", "chunkMode", "retrievalMode", "queryMode",
                           "postprocessMode"}
    if variable_field not in supported_variables:
        raise ValueError(f"Unsupported experiment variable: {variable_field}")
    fixed_fields = ["gitCommit", "corpusLockSha256", "split", "embeddingModel",
                    "tokenizerFingerprint", "candidateLimit", "canonicalMode", "chunkMode",
                    "retrievalMode", "queryMode", "queryRewriteFingerprint",
                    "postprocessMode", "dedupFingerprint", "retrievalPoolLimit",
                    "caseProfile", "sourceDiversityFingerprint", "sourceDiversityHeadLimit",
                    "sourceDiversityPerSourceHeadCap",
                    "lexicalRankerFingerprint", "fusionFingerprint"]
    fixed_fields.remove(variable_field)
    drift = [field for field in fixed_fields if e0.get(field) != e1.get(field)]
    if drift:
        raise ValueError(f"Experiment controls differ: {drift}")
    e0_cases = {case["caseId"]: case for case in e0["metrics"]["caseResults"]}
    e1_cases = {case["caseId"]: case for case in e1["metrics"]["caseResults"]}
    if e0_cases.keys() != e1_cases.keys():
        raise ValueError("Paired runs do not contain the same case IDs")
    case_ids = sorted(e0_cases)
    for case_id in case_ids:
        for field in ("category", "primaryCategory", "language", "goldAnchorIds",
                      "fixedGoldChunkIdsByAnchor", "mountedSourceVersions",
                      "unmountedSourceVersions", "goldSourceVersions"):
            if e0_cases[case_id].get(field) != e1_cases[case_id].get(field):
                raise ValueError(f"Case metadata drift for {case_id}: {field}")
        if variable_field in {"chunkMode", "retrievalMode", "queryMode", "postprocessMode"}:
            fixed_gold = e0_cases[case_id].get("fixedGoldChunkIdsByAnchor")
            if not isinstance(fixed_gold, dict) or set(fixed_gold) != set(
                    e0_cases[case_id]["goldAnchorIds"]):
                raise ValueError(f"Missing fixed gold-to-child mapping for {case_id}")
        for run_label, case in (("e0", e0_cases[case_id]), ("e1", e1_cases[case_id])):
            candidates = case.get("candidates")
            if not isinstance(candidates, list) or len(candidates) > e0["candidateLimit"]:
                raise ValueError(f"Missing or invalid raw candidates for {run_label}:{case_id}")
            if [candidate.get("rank") for candidate in candidates] != list(
                    range(1, len(candidates) + 1)):
                raise ValueError(f"Candidate ranks are not contiguous for {run_label}:{case_id}")
            if any(candidate.get("chunkId") == "unknown" for candidate in candidates):
                raise ValueError(f"Unknown candidate chunk for {run_label}:{case_id}")
        if variable_field == "retrievalMode":
            for lane in ("denseCandidates", "lexicalCandidates"):
                before = e0_cases[case_id].get(lane)
                after = e1_cases[case_id].get(lane)
                if not isinstance(before, list) or not isinstance(after, list):
                    raise ValueError(f"Missing {lane} for retrieval experiment: {case_id}")
                before_chunks = [candidate.get("chunkId") for candidate in before]
                after_chunks = [candidate.get("chunkId") for candidate in after]
                if before_chunks != after_chunks:
                    raise ValueError(f"{lane} drift for {case_id}")
        if variable_field == "postprocessMode":
            before = e0_cases[case_id].get("retrievalPoolCandidates")
            after = e1_cases[case_id].get("retrievalPoolCandidates")
            if not isinstance(before, list) or not isinstance(after, list):
                raise ValueError(f"Missing retrievalPoolCandidates for postprocess experiment: {case_id}")
            if len(before) > e0["retrievalPoolLimit"] or len(after) > e0["retrievalPoolLimit"]:
                raise ValueError(f"Invalid retrievalPoolCandidates size for {case_id}")
            before_chunks = [candidate.get("chunkId") for candidate in before]
            after_chunks = [candidate.get("chunkId") for candidate in after]
            if before_chunks != after_chunks:
                raise ValueError(f"retrievalPoolCandidates drift for {case_id}")

    aggregates = {}
    for metric_index, metric in enumerate(METRICS):
        e0_values = [case_value(e0_cases[case_id]["rank"], metric) for case_id in case_ids]
        e1_values = [case_value(e1_cases[case_id]["rank"], metric) for case_id in case_ids]
        deltas = [after - before for before, after in zip(e0_values, e1_values)]
        aggregates[metric] = {
            "e0": sum(e0_values) / len(e0_values),
            "e0Ci95": interval(e0_values, metric, 2026072200 + metric_index),
            "e1": sum(e1_values) / len(e1_values),
            "e1Ci95": interval(e1_values, metric, 2026072210 + metric_index),
            "delta": sum(deltas) / len(deltas),
            "deltaCi95": bootstrap_mean(deltas, 2026072220 + metric_index),
        }

    labels = sorted({label for case in e0_cases.values() for label in slices(case)})
    slice_results = []
    for index, label in enumerate(labels):
        selected = [case_id for case_id in case_ids if label in slices(e0_cases[case_id])]
        e0_values = [case_value(e0_cases[case_id]["rank"], "recallAt10") for case_id in selected]
        e1_values = [case_value(e1_cases[case_id]["rank"], "recallAt10") for case_id in selected]
        slice_results.append({
            "label": label,
            "count": len(selected),
            "e0RecallAt10": sum(e0_values) / len(selected),
            "e0Ci95": wilson(int(sum(e0_values)), len(selected)),
            "e1RecallAt10": sum(e1_values) / len(selected),
            "e1Ci95": wilson(int(sum(e1_values)), len(selected)),
            "delta": (sum(e1_values) - sum(e0_values)) / len(selected),
            "deltaCi95": bootstrap_mean(
                [after - before for before, after in zip(e0_values, e1_values)],
                2026072300 + index,
            ),
        })

    e0_mapped = [case_id for case_id in case_ids if e0_cases[case_id].get("mappable")]
    e1_mapped = [case_id for case_id in case_ids if e1_cases[case_id].get("mappable")]
    conditional = {"e0CaseCount": len(e0_mapped), "e1CaseCount": len(e1_mapped), "metrics": {}}
    for metric_index, metric in enumerate(METRICS):
        e0_values = [case_value(e0_cases[case_id]["rank"], metric) for case_id in e0_mapped]
        e1_values = [case_value(e1_cases[case_id]["rank"], metric) for case_id in e1_mapped]
        conditional["metrics"][metric] = {
            "e0": sum(e0_values) / len(e0_values),
            "e0Ci95": interval(e0_values, metric, 2026072400 + metric_index),
            "e1": sum(e1_values) / len(e1_values),
            "e1Ci95": interval(e1_values, metric, 2026072410 + metric_index),
        }

    result = {
        "schemaVersion": "material-rag-dense-paired-comparison-v1",
        "status": "comparable",
        "scope": "answerable-dense-eligible-text-table-and-multi-evidence",
        "caseCount": len(case_ids),
        "controls": {field: e0.get(field) for field in fixed_fields},
        "experimentVariable": {
            "field": variable_field,
            "baseline": e0.get(variable_field),
            "candidate": e1.get(variable_field),
        },
        "modes": {
            "canonical": {"e0": e0["canonicalMode"], "e1": e1["canonicalMode"]},
            "retrieval": {"e0": e0.get("retrievalMode"), "e1": e1.get("retrievalMode")},
            "query": {"e0": e0.get("queryMode"), "e1": e1.get("queryMode")},
            "postprocess": {
                "e0": e0.get("postprocessMode"), "e1": e1.get("postprocessMode")
            },
        },
        "chunkCounts": {"e0": e0["chunkCount"], "e1": e1["chunkCount"]},
        "mapping": {
            "e0Count": len(e0_mapped),
            "e0Rate": len(e0_mapped) / len(case_ids),
            "e0Ci95": wilson(len(e0_mapped), len(case_ids)),
            "e1Count": len(e1_mapped),
            "e1Rate": len(e1_mapped) / len(case_ids),
            "e1Ci95": wilson(len(e1_mapped), len(case_ids)),
            "delta": (len(e1_mapped) - len(e0_mapped)) / len(case_ids),
        },
        "conditionalOnMapping": conditional,
        "aggregates": aggregates,
        "slices": slice_results,
    }
    if variable_field == "postprocessMode":
        # Activation is a separate gate from relevance quality for deduplication experiments.
        result["postprocessActivation"] = postprocess_activation(e0_cases, e1_cases, case_ids)
        e0_diversity = source_diversity(e0_cases, case_ids)
        e1_diversity = source_diversity(e1_cases, case_ids)
        result["sourceDiversity"] = {
            "e0": e0_diversity,
            "e1": e1_diversity,
            "deltas": {
                metric: e1_diversity[metric] - e0_diversity[metric]
                for metric in ("meanMountedCoverageAt10", "meanUniqueSourcesAt10",
                               "meanMaxSourceShareAt10", "goldSourceRecallAt10",
                               "goldSourceRecallAt40")
            },
        }
    return result


def attach_corpus_lock_snapshot(result: dict, snapshot: Path) -> None:
    """Attach a run lock only after verifying it matches the paired raw results."""
    actual = hashlib.sha256(snapshot.read_bytes()).hexdigest()
    expected = result["controls"]["corpusLockSha256"]
    if actual != expected:
        raise ValueError(f"Corpus-lock snapshot hash differs: expected {expected}, got {actual}")
    result["controls"]["corpusLockSnapshot"] = str(snapshot)


def percent(value: float) -> str:
    return f"{value:.3f}"


def ci(value: list[float]) -> str:
    return f"[{value[0]:.3f}, {value[1]:.3f}]"


def markdown_report(result: dict) -> str:
    lines = [
        "# E0/E1 paired validation comparison on the frozen 450-case corpus",
        "",
        f"Paired dense-retrieval cases: **{result['caseCount']}**. Holdout remained sealed.",
        "This is the answerable dense-eligible subset, not a score over visual, OCR or no-answer cases.",
        f"Run corpus lock: `{result['controls'].get('corpusLockSnapshot', 'not supplied')}`",
        f"(SHA-256 `{result['controls']['corpusLockSha256']}`).",
        "All intervals below are 95%; recall uses Wilson intervals and paired deltas/MRR use a",
        "deterministic 10,000-sample percentile bootstrap.",
        "",
        "| Metric | E0 | E0 CI | E1 | E1 CI | Delta | Delta CI |",
        "|---|---:|:---:|---:|:---:|---:|:---:|",
    ]
    for metric, value in result["aggregates"].items():
        lines.append(
            f"| {metric} | {percent(value['e0'])} | {ci(value['e0Ci95'])} | "
            f"{percent(value['e1'])} | {ci(value['e1Ci95'])} | "
            f"{percent(value['delta'])} | {ci(value['deltaCi95'])} |"
        )
    lines.extend([
        "",
        "## Mapping and conditional retrieval",
        "",
        "| Stage | E0 | E1 | Delta |",
        "|---|---:|---:|---:|",
        f"| Gold mapping | {result['mapping']['e0Count']}/{result['caseCount']} "
        f"({percent(result['mapping']['e0Rate'])}) | {result['mapping']['e1Count']}/"
        f"{result['caseCount']} ({percent(result['mapping']['e1Rate'])}) | "
        f"{percent(result['mapping']['delta'])} |",
    ])
    for metric in ("recallAt10", "recallAt40", "mrrAt10"):
        value = result["conditionalOnMapping"]["metrics"][metric]
        lines.append(
            f"| Conditional {metric} | {percent(value['e0'])} {ci(value['e0Ci95'])} | "
            f"{percent(value['e1'])} {ci(value['e1Ci95'])} | — |"
        )
    lines.extend([
        "",
        "## Recall@10 slices",
        "",
        "| Slice | n | E0 (95% CI) | E1 (95% CI) | Delta (paired 95% CI) |",
        "|---|---:|:---:|:---:|:---:|",
    ])
    for value in result["slices"]:
        lines.append(
            f"| {value['label']} | {value['count']} | {percent(value['e0RecallAt10'])} "
            f"{ci(value['e0Ci95'])} | {percent(value['e1RecallAt10'])} "
            f"{ci(value['e1Ci95'])} | {percent(value['delta'])} "
            f"{ci(value['deltaCi95'])} |"
        )
    r10 = result["aggregates"]["recallAt10"]
    r40 = result["aggregates"]["recallAt40"]
    mrr = result["aggregates"]["mrrAt10"]
    lines.extend([
        "",
        "## Decision",
        "",
        f"E1 improves Recall@10 by {percent(r10['delta'])}, Recall@40 by "
        f"{percent(r40['delta'])}, and MRR@10 by {percent(mrr['delta'])}. The improvement "
        f"generalizes to Validation and mapping improves by {percent(result['mapping']['delta'])}, "
        "but E1 remains below the 0.90/0.95/0.75 end-to-end promotion gates. "
        "Keep the representation change as a proven component; do not declare the dense-only "
        "pipeline complete. Run E2 on Development; if its gain is below 0.02, continue with E3, "
        "then return to Validation.",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("e0", type=Path)
    parser.add_argument("e1", type=Path)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--markdown-out", type=Path)
    parser.add_argument("--corpus-lock-snapshot", type=Path)
    parser.add_argument("--variable-field",
                        choices=("canonicalMode", "chunkMode", "retrievalMode", "queryMode",
                                 "postprocessMode"),
                        default="canonicalMode")
    args = parser.parse_args()
    e0 = json.loads(args.e0.read_text(encoding="utf-8"))
    e1 = json.loads(args.e1.read_text(encoding="utf-8"))
    result = compare(e0, e1, args.variable_field)
    if args.corpus_lock_snapshot:
        # Keep results tied to the exact historical lock even after analysis tools evolve.
        attach_corpus_lock_snapshot(result, args.corpus_lock_snapshot)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    print(output, end="")
    for path, content in ((args.json_out, output),
                          (args.markdown_out, markdown_report(result))):
        if path:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    main()
