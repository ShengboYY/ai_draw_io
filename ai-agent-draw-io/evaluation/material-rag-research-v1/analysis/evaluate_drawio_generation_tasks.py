#!/usr/bin/env python3
"""Validate editable draw.io XML and evidence citations for frozen generation tasks."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path


def cells(xml: str) -> list[ET.Element]:
    """Parse only local model output; malformed XML is a scored failure, never repaired."""
    root = ET.fromstring(xml)
    return list(root.iter("mxCell"))


def citation_anchor_ids(citations: object) -> set[str]:
    """Normalize the two citation shapes accepted by the generation response contract."""
    if not isinstance(citations, list):
        return set()
    return {
        value if isinstance(value, str) else value.get("anchorId", "")
        for value in citations
        if isinstance(value, str) or isinstance(value, dict)
    } - {""}


def evaluate(task: dict, response: dict) -> dict:
    citations = citation_anchor_ids(response.get("citations", []))
    required = set(task["citationAssertions"]["mustCiteAnchors"])
    result = {"taskId": task["taskId"], "xmlParseable": False, "xmlAssertionsPassed": False,
              "citationAssertionsPassed": False, "completed": False}
    try:
        graph_cells = cells(response.get("xml", ""))
    except ET.ParseError:
        graph_cells = []
    else:
        result["xmlParseable"] = True
        vertices = [cell for cell in graph_cells if cell.get("vertex") == "1"]
        edges = [cell for cell in graph_cells if cell.get("edge") == "1"]
        assertion = task["xmlAssertions"]
        labels = {cell.get("value", "") for cell in graph_cells}
        result["xmlAssertionsPassed"] = (
            len(vertices) >= assertion.get("minVertices", 0)
            and len(edges) >= assertion.get("minEdges", 0)
            and set(assertion.get("requiredLabels", [])).issubset(labels)
        )
    citation_assertion = task["citationAssertions"]
    result["citationAssertionsPassed"] = (
        len(citations) >= citation_assertion["minimumCitations"] and required.issubset(citations)
    )
    result["completed"] = result["xmlParseable"] and result["xmlAssertionsPassed"] \
        and result["citationAssertionsPassed"]
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks", type=Path, required=True)
    parser.add_argument("--responses", type=Path, required=True)
    parser.add_argument("--split", choices=("development", "validation", "holdout"), required=True)
    parser.add_argument("--json-out", type=Path, required=True)
    args = parser.parse_args()
    tasks = [task for task in json.loads(args.tasks.read_text())["tasks"] if task["split"] == args.split]
    responses = {value["taskId"]: value for value in json.loads(args.responses.read_text())["responses"]}
    results = [evaluate(task, responses.get(task["taskId"], {})) for task in tasks]
    count = len(results)
    args.json_out.write_text(json.dumps({"split": args.split, "taskCount": count, "results": results,
        "xmlParseRate": sum(value["xmlParseable"] for value in results) / count if count else 0,
        "citationCompletenessRate": sum(value["citationAssertionsPassed"] for value in results) / count if count else 0,
        "completionRate": sum(value["completed"] for value in results) / count if count else 0}, indent=2) + "\n")


if __name__ == "__main__":
    main()
