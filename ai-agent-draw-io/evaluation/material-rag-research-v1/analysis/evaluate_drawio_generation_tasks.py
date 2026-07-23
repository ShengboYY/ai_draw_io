#!/usr/bin/env python3
"""Validate editable draw.io XML and evidence citations for frozen generation tasks."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
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
    graph_cells = list(graph.iter("mxCell"))
    cell_ids = [cell.get("id") for cell in graph_cells if cell.get("id")]
    if len(set(cell_ids)) != len(cell_ids):
        raise ET.ParseError("draw.io XML contains duplicate cell IDs")
    return graph_cells


def citation_anchor_ids(citations: object, task: dict, anchors: dict[str, dict]) -> set[str]:
    """Accept only citations whose anchor, version and page match hydrated source evidence."""
    if not isinstance(citations, list):
        return set()
    valid = set()
    allowed_sources = set(task.get("allowedSourceVersions", [task.get("sourceVersion")]))
    for citation in citations:
        if not isinstance(citation, dict):
            continue
        anchor_id = citation.get("anchorId")
        if not isinstance(anchor_id, str):
            continue
        anchor = anchors.get(anchor_id)
        if anchor is not None and citation.get("sourceVersion") in allowed_sources \
                and citation["sourceVersion"] == f"{anchor['source']}:{anchor['version']}" \
                and citation.get("page") == anchor.get("page"):
            valid.add(anchor_id)
    return valid


def normalized_label(value: str) -> str:
    """Compare draw.io label text without treating HTML styling as diagram content."""
    visible = re.sub(r"<[^>]+>", " ", html.unescape(value))
    return " ".join(visible.split()).casefold()


def normalized_policy_label(value: str) -> str:
    """Apply v2's explicitly opted-in presentation normalization to a visible label."""
    # Hyphen variants are presentation differences only when a v2 policy opts in.
    return re.sub(r"[-‐‑‒–—―]", " ", normalized_label(value))


def label_matches(required_label: str, cell_label: str) -> bool:
    """Match a normalized label as words, without confusing V1 with V10."""
    required = normalized_label(required_label)
    return bool(re.search(r"(?<!\w)" + re.escape(required) + r"(?!\w)", cell_label))


def fact_matches(fact: dict, vertices: list[ET.Element], edges: list[ET.Element]) -> bool:
    """Check an explicitly frozen fact against editable vertices and/or edge labels."""
    locations = set(fact.get("locations", ("vertex",)))
    scoped_cells = ([] if "vertex" not in locations else vertices) \
        + ([] if "edge" not in locations else edges)
    values = [normalized_policy_label(cell.get("value", "")) for cell in scoped_cells]
    for accepted in fact.get("acceptedLabels", []):
        label = normalized_policy_label(str(accepted.get("text", "")))
        if not label:
            continue
        if accepted.get("matchMode", "word") == "substring":
            if any(label in value for value in values):
                return True
        elif any(label_matches(label, value) for value in values):
            return True
    return False


def xml_assertions_pass(task: dict, vertices: list[ET.Element], edges: list[ET.Element],
                        acceptance_policy: dict | None) -> bool:
    """Apply legacy assertions unless a frozen task policy defines fact-level acceptance."""
    assertion = task["xmlAssertions"]
    override = (acceptance_policy or {}).get("taskOverrides", {}).get(task["taskId"], {})
    min_vertices = override.get("minVertices", assertion.get("minVertices", 0))
    min_edges = override.get("minEdges", assertion.get("minEdges", 0))
    if len(vertices) < min_vertices or len(edges) < min_edges:
        return False
    required_facts = override.get("requiredFacts")
    if required_facts is not None:
        return all(fact_matches(fact, vertices, edges) for fact in required_facts)
    labels = [normalized_label(cell.get("value", "")) for cell in vertices]
    return all(any(label_matches(label, value) for value in labels)
               for label in assertion.get("requiredLabels", []))


def validate_acceptance_policy(policy: dict, task_fixture_sha256: str) -> None:
    """Reject a post-hoc policy when it is aimed at a different frozen task set."""
    if policy.get("schemaVersion") != "material-rag-drawio-generation-acceptance-policy-v2":
        raise ValueError("unsupported acceptance policy schema")
    policy_fixture_sha = policy.get("sourceRun", {}).get("taskFixtureSha256")
    if policy_fixture_sha != task_fixture_sha256:
        raise ValueError("acceptance policy targets a different frozen task fixture")


def cell_map(graph_cells: list[ET.Element]) -> dict[str, ET.Element]:
    """Index stable draw.io cell identifiers for edit-preservation checks."""
    return {cell_id: cell for cell in graph_cells if (cell_id := cell.get("id"))}


def geometry_x(cell: ET.Element) -> float | None:
    geometry = cell.find("mxGeometry")
    if geometry is None:
        return None
    try:
        return float(geometry.get("x", ""))
    except ValueError:
        return None


def non_geometry_children(cell: ET.Element) -> list[bytes]:
    """Serialize non-geometry children so layout-only edits cannot rewrite other cell content."""
    return [ET.tostring(child) for child in cell if child.tag != "mxGeometry"]


def edit_assertions_pass(task: dict, graph_cells: list[ET.Element]) -> bool:
    """Verify that an edit changed the requested cells while preserving protected structure."""
    assertion = task.get("editAssertions")
    if not assertion:
        return True
    indexed = cell_map(graph_cells)
    if assertion.get("geometryOnly"):
        try:
            input_cells = cell_map(cells(task.get("inputXml", "")))
        except (ET.ParseError, TypeError):
            return False
        if set(indexed) != set(input_cells):
            return False
        for cell_id, input_cell in input_cells.items():
            output_cell = indexed[cell_id]
            if input_cell.attrib != output_cell.attrib \
                    or non_geometry_children(input_cell) != non_geometry_children(output_cell):
                return False
    if any(cell_id not in indexed for cell_id in assertion.get("preserveCellIds", [])):
        return False
    for cell_id, expected in assertion.get("preserveCellValues", {}).items():
        if cell_id not in indexed or normalized_label(indexed[cell_id].get("value", "")) != normalized_label(expected):
            return False
    for cell_id, attributes in assertion.get("preserveCellAttributes", {}).items():
        if cell_id not in indexed or any(indexed[cell_id].get(name) != value for name, value in attributes.items()):
            return False
    for cell_id, expected in assertion.get("requiredCellValues", {}).items():
        if cell_id not in indexed or indexed[cell_id].get("vertex") != "1" or not indexed[cell_id].get("parent") \
                or not label_matches(expected, normalized_label(indexed[cell_id].get("value", ""))):
            return False
    for cell_id, attributes in assertion.get("requiredCellAttributes", {}).items():
        if cell_id not in indexed or any(indexed[cell_id].get(name) != value for name, value in attributes.items()):
            return False
    if any(cell_id in indexed for cell_id in assertion.get("forbiddenCellIds", [])):
        return False
    columns = assertion.get("columns")
    if columns:
        left = [geometry_x(indexed[cell_id]) for cell_id in columns.get("leftCellIds", [])
                if cell_id in indexed]
        right = [geometry_x(indexed[cell_id]) for cell_id in columns.get("rightCellIds", [])
                 if cell_id in indexed]
        expected_count = len(columns.get("leftCellIds", [])) + len(columns.get("rightCellIds", []))
        if len(left) + len(right) != expected_count or any(value is None for value in left + right):
            return False
        if not left or not right or max(left) + columns.get("minimumHorizontalGap", 0) > min(right):
            return False
    return True


def evaluate(task: dict, response: dict, anchors: dict[str, dict],
             acceptance_policy: dict | None = None) -> dict:
    """Score malformed model payloads as failures instead of aborting the batch."""
    if not isinstance(response, dict):
        response = {}
    citations = citation_anchor_ids(response.get("citations", []), task, anchors)
    required = set(task["citationAssertions"]["mustCiteAnchors"])
    result = {"taskId": task["taskId"], "xmlParseable": False, "xmlAssertionsPassed": False,
              "editAssertionsPassed": False, "requiredCitationContractPassed": False,
              "completed": False}
    xml = response.get("xml", "")
    if not isinstance(xml, str):
        graph_cells = []
    else:
        try:
            graph_cells = cells(xml)
        except (ET.ParseError, TypeError):
            graph_cells = []
        else:
            result["xmlParseable"] = True
            vertices = [cell for cell in graph_cells
                        if cell.get("vertex") == "1" and cell.get("parent")]
            edges = [cell for cell in graph_cells if cell.get("edge") == "1" and cell.get("parent")]
            result["xmlAssertionsPassed"] = xml_assertions_pass(
                task, vertices, edges, acceptance_policy
            )
            result["editAssertionsPassed"] = edit_assertions_pass(task, graph_cells)
    citation_assertion = task["citationAssertions"]
    result["requiredCitationContractPassed"] = (
        len(citations) >= citation_assertion["minimumCitations"] and required.issubset(citations)
    )
    result["completed"] = result["xmlParseable"] and result["xmlAssertionsPassed"] \
        and result["editAssertionsPassed"] and result["requiredCitationContractPassed"]
    return result


def claim_metrics(claim_reviews: dict | None, tasks: list[dict] | None = None) -> dict:
    """Aggregate independent claim review; never infer full citation quality from required anchors."""
    if claim_reviews is None:
        return {"claimMetricsStatus": "not_evaluated"}
    reviewers = claim_reviews.get("reviewers", [])
    if claim_reviews.get("schemaVersion") != "material-rag-claim-review-v1" \
            or len(set(reviewers)) < 2 or any(not str(reviewer).strip() for reviewer in reviewers) \
            or not str(claim_reviews.get("adjudicationMethod", "")).strip():
        raise ValueError("claim review requires two named reviewers, adjudication and v1 schema")
    if tasks is None:
        raise ValueError("claim review requires the frozen task claim universe")
    expected = {
        task["taskId"]: {claim["claimId"]: claim for claim in task.get("claimAssertions", {}).get("requiredClaims", [])}
        for task in tasks
    }
    if not expected or any(not claims for claims in expected.values()):
        raise ValueError("every evaluated task must freeze at least one required claim")
    reviews = claim_reviews.get("reviews", [])
    if len({review.get("taskId") for review in reviews}) != len(reviews) \
            or {review.get("taskId") for review in reviews} != set(expected):
        raise ValueError("claim review must cover every task exactly once")
    claims = []
    required_specs = []
    for review in reviews:
        reviewed = review.get("claims", [])
        claim_ids = [claim.get("claimId") for claim in reviewed]
        if len(set(claim_ids)) != len(claim_ids) or set(claim_ids) != set(expected[review["taskId"]]):
            raise ValueError(f"claim review must cover the frozen claims for {review['taskId']}")
        claims.extend(reviewed)
        required_specs.extend(expected[review["taskId"]][claim_id] for claim_id in claim_ids)
    fields = ("claimAnswered", "hasCitation", "citationSupportsClaim", "claimCorrect", "faithful")
    if not claims or any(not isinstance(claim.get(field), bool) for claim in claims for field in fields):
        raise ValueError("claim review must contain all five boolean judgements for every claim")
    required = [claim for claim, spec in zip(claims, required_specs) if spec.get("requiresCitation")]
    cited = [claim for claim in claims if claim["hasCitation"]]
    return {
        "claimMetricsStatus": "evaluated",
        "reviewedClaimCount": len(claims),
        "citationRequiredClaimCount": len(required),
        "citedClaimCount": len(cited),
        "answerCompletenessRate": sum(claim["claimAnswered"] for claim in claims) / len(claims),
        "claimCitationCompletenessRate": (
            sum(claim["hasCitation"] for claim in required) / len(required) if required else 1.0
        ),
        "citationPrecisionRate": (
            sum(claim["citationSupportsClaim"] for claim in cited) / len(cited) if cited else 1.0
        ),
        "claimCorrectnessRate": sum(claim["claimCorrect"] for claim in claims) / len(claims),
        "faithfulnessRate": sum(claim["faithful"] for claim in claims) / len(claims),
    }


def summarize(results: list[dict], split: str, reviews: dict | None = None,
              tasks: list[dict] | None = None, task_fixture_sha256: str | None = None) -> dict:
    count = len(results)
    return {
        "schemaVersion": "material-rag-drawio-generation-evaluation-v2",
        "split": split,
        "taskCount": count,
        "taskFixtureSha256": task_fixture_sha256,
        "results": results,
        "xmlParseRate": sum(value["xmlParseable"] for value in results) / count if count else 0,
        "requiredCitationContractRate": sum(value["requiredCitationContractPassed"] for value in results) / count if count else 0,
        "completionRate": sum(value["completed"] for value in results) / count if count else 0,
        **claim_metrics(reviews, tasks),
    }


def response_map(responses: list[dict]) -> dict[str, dict]:
    """Reject duplicate task responses so a later record cannot silently select the scored output."""
    task_ids = [response.get("taskId") for response in responses]
    if len(set(task_ids)) != len(task_ids):
        raise ValueError("responses contain duplicate task IDs")
    return {response["taskId"]: response for response in responses}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks", type=Path, required=True)
    parser.add_argument("--responses", type=Path, required=True)
    parser.add_argument("--ground-truth", type=Path, required=True)
    parser.add_argument("--split", choices=("development", "validation", "holdout"), required=True)
    parser.add_argument("--claim-reviews", type=Path)
    parser.add_argument("--acceptance-policy", type=Path)
    parser.add_argument("--json-out", type=Path, required=True)
    args = parser.parse_args()
    tasks = [task for task in json.loads(args.tasks.read_text())["tasks"] if task["split"] == args.split]
    responses = response_map(json.loads(args.responses.read_text())["responses"])
    anchors = {anchor["anchorId"]: anchor
               for anchor in json.loads(args.ground_truth.read_text())["anchors"]}
    task_fixture_sha256 = hashlib.sha256(args.tasks.read_bytes()).hexdigest()
    policy = json.loads(args.acceptance_policy.read_text()) if args.acceptance_policy else None
    if policy is not None:
        validate_acceptance_policy(policy, task_fixture_sha256)
    results = [evaluate(task, responses.get(task["taskId"], {}), anchors, policy) for task in tasks]
    reviews = json.loads(args.claim_reviews.read_text()) if args.claim_reviews else None
    args.json_out.write_text(json.dumps(
        summarize(results, args.split, reviews, tasks, task_fixture_sha256), indent=2
    ) + "\n")


if __name__ == "__main__":
    main()
