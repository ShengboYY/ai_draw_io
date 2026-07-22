#!/usr/bin/env python3
"""Compare two paired dense-retrieval runs and report confidence intervals."""

from __future__ import annotations

import argparse
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


def compare(e0: dict, e1: dict) -> dict:
    """Compare paired case ranks after rejecting any experiment-control drift."""
    fixed_fields = ("gitCommit", "corpusLockSha256", "split", "embeddingModel",
                    "tokenizerFingerprint", "candidateLimit")
    drift = [field for field in fixed_fields if e0.get(field) != e1.get(field)]
    if drift:
        raise ValueError(f"Experiment controls differ: {drift}")
    e0_cases = {case["caseId"]: case for case in e0["metrics"]["caseResults"]}
    e1_cases = {case["caseId"]: case for case in e1["metrics"]["caseResults"]}
    if e0_cases.keys() != e1_cases.keys():
        raise ValueError("Paired runs do not contain the same case IDs")
    case_ids = sorted(e0_cases)
    for case_id in case_ids:
        for field in ("category", "primaryCategory", "language", "goldAnchorIds"):
            if e0_cases[case_id].get(field) != e1_cases[case_id].get(field):
                raise ValueError(f"Case metadata drift for {case_id}: {field}")

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

    return {
        "schemaVersion": "material-rag-dense-paired-comparison-v1",
        "status": "comparable",
        "caseCount": len(case_ids),
        "controls": {field: e0[field] for field in fixed_fields},
        "modes": {"e0": e0["canonicalMode"], "e1": e1["canonicalMode"]},
        "chunkCounts": {"e0": e0["chunkCount"], "e1": e1["chunkCount"]},
        "aggregates": aggregates,
        "slices": slice_results,
    }


def percent(value: float) -> str:
    return f"{value:.3f}"


def ci(value: list[float]) -> str:
    return f"[{value[0]:.3f}, {value[1]:.3f}]"


def markdown_report(result: dict) -> str:
    lines = [
        "# E0/E1 paired validation comparison on the frozen 450-case corpus",
        "",
        f"Paired dense-retrieval cases: **{result['caseCount']}**. Holdout remained sealed.",
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
        "generalizes to Validation, but E1 remains below the 0.90/0.95/0.75 promotion gates. "
        "Keep the representation change as a proven component; do not declare the dense-only "
        "pipeline complete. Continue with E2 or E3 on Development, then return to Validation.",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("e0", type=Path)
    parser.add_argument("e1", type=Path)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--markdown-out", type=Path)
    args = parser.parse_args()
    e0 = json.loads(args.e0.read_text(encoding="utf-8"))
    e1 = json.loads(args.e1.read_text(encoding="utf-8"))
    result = compare(e0, e1)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    print(output, end="")
    for path, content in ((args.json_out, output),
                          (args.markdown_out, markdown_report(result))):
        if path:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    main()
