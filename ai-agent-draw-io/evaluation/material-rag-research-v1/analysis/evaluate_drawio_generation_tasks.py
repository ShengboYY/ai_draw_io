#!/usr/bin/env python3
"""Validate editable draw.io XML and evidence citations for frozen generation tasks."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path


def cells(xml: str) -> list[ET.Element]:
    """Parse only editable draw.io XML; malformed or foreign XML is a scored failure."""
    root = ET.fromstring(xml)
    if root.tag == "mxGraphModel":
        graph = root
    elif root.tag == "mxfile":
        graph = root.find(".//mxGraphModel")
        if graph is None:
            raise ET.ParseError("mxfile contains no mxGraphModel")
    else:
        raise ET.ParseError("root is not draw.io XML")
    return list(graph.iter("mxCell"))


def citation_anchor_ids(citations: object, task: dict, anchors: dict[str, dict]) -> set[str]:
    """Accept only citations whose anchor, version and page match hydrated source evidence."""
    if not isinstance(citations, list):
        return set()
    valid = set()
    for citation in citations:
        if not isinstance(citation, dict):
            continue
        anchor_id = citation.get("anchorId")
        anchor = anchors.get(anchor_id)
        if anchor is not None and citation.get("sourceVersion") == task["sourceVersion"] \
                and citation["sourceVersion"] == f"{anchor['source']}:{anchor['version']}" \
                and citation.get("page") == anchor.get("page"):
            valid.add(anchor_id)
    return valid


def evaluate(task: dict, response: dict, anchors: dict[str, dict]) -> dict:
    citations = citation_anchor_ids(response.get("citations", []), task, anchors)
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
    parser.add_argument("--ground-truth", type=Path, required=True)
    parser.add_argument("--split", choices=("development", "validation", "holdout"), required=True)
    parser.add_argument("--json-out", type=Path, required=True)
    args = parser.parse_args()
    tasks = [task for task in json.loads(args.tasks.read_text())["tasks"] if task["split"] == args.split]
    responses = {value["taskId"]: value for value in json.loads(args.responses.read_text())["responses"]}
    anchors = {anchor["anchorId"]: anchor
               for anchor in json.loads(args.ground_truth.read_text())["anchors"]}
    results = [evaluate(task, responses.get(task["taskId"], {}), anchors) for task in tasks]
    count = len(results)
    args.json_out.write_text(json.dumps({"split": args.split, "taskCount": count, "results": results,
        "xmlParseRate": sum(value["xmlParseable"] for value in results) / count if count else 0,
        "citationCompletenessRate": sum(value["citationAssertionsPassed"] for value in results) / count if count else 0,
        "completionRate": sum(value["completed"] for value in results) / count if count else 0}, indent=2) + "\n")


if __name__ == "__main__":
    main()
