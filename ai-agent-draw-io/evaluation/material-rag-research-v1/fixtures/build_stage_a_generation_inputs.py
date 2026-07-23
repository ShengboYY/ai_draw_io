#!/usr/bin/env python3
"""Build frozen E7/E8 inputs from the reviewed Stage A Ready cases."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "fixtures" / "generated" / "stage-a-generation"


# Each entry supplies model-visible, synthetic source content that is deliberately separate from evaluator-only assertions.
SPECS = [
    ("sta-ready-01", "sta-release-governance:v1", [
        ("rg-gateway", "Approved deployment responsibility: Gateway validates ingress and forwards approved traffic to Worker."),
        ("rg-worker", "Approved deployment responsibility: Worker executes the release workload after Gateway validation."),
        ("rg-audit-store", "Approved deployment responsibility: Audit Store records the immutable release audit event from Worker."),
    ], ["Gateway", "Worker", "Audit Store"], 3, 2, None),
    ("sta-ready-02", "sta-incident-command:v1", [
        ("ic-commander", "Incident Commander owns the incident decision lane and assigns the response priority."),
        ("ic-communications", "Communications Lead owns stakeholder updates and receives the Commander handoff."),
        ("ic-escalation-window", "Escalate an unresolved severity-one incident to the Incident Commander within 15 minutes."),
    ], ["Incident Commander", "Communications Lead", "15 minutes"], 3, 1, None),
    ("sta-ready-03", "sta-data-retention:v1", [
        ("dr-archive-retention", "The Archive node retention period is seven years. The record must state Retention: 7 years."),
    ], ["Archive", "7 years"], 2, 0, "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='archive' value='Archive — Retention: 30 days' vertex='1' parent='1'/></root></mxGraphModel>"),
    ("sta-ready-04", "sta-payment-controls:v1", [
        ("pc-two-person", "Review must approve Release using a two-person approval control."),
        ("pc-control-number", "The required control identifier for the Review to Release relationship is PC-204."),
    ], ["Review", "Release", "PC-204"], 2, 1, None),
    ("sta-ready-05", "sta-network-zones:v3", [
        ("nz-v3-quarantine", "Version 3 segmentation requires Public Zone to Private Zone traffic to pass through the Quarantine Path."),
    ], ["Public Zone", "Private Zone", "Quarantine Path"], 3, 2, None),
    ("sta-ready-06", "sta-service-ownership:v1", [
        ("so-canonical-name", "Use the canonical English service names API Gateway, Billing Worker, and Audit Store. Ownership is Platform Team, Payments Team, and Governance Team respectively."),
    ], ["API Gateway", "Billing Worker", "Audit Store"], 3, 0, None),
    ("sta-ready-07", "sta-change-calendar:v1", [
        ("cc-owner", "The change calendar assigns Database Change to DBA Owner and Application Change to Application Owner."),
        ("cc-approval-window", "Both changes require the approval window Tue 10:00–12:00."),
    ], ["DBA Owner", "Application Owner", "Tue 10:00–12:00"], 3, 1, None),
    ("sta-ready-08", "sta-warehouse-visual:v1", [
        ("wv-exception-flow", "Selected warehouse figure: Intake goes to Inspect; an exception goes to Supervisor Review; a cleared item goes to Dispatch."),
    ], ["Intake", "Inspect", "Supervisor Review", "Dispatch"], 4, 3, "warehouse"),
    ("sta-ready-09", "sta-field-scan:v1", [
        ("fs-handoff-flow", "Scanned field handoff: Field Technician records the handoff, Decision: complete?, No returns to Field Technician, Yes goes to Operations Lead."),
    ], ["Field Technician", "complete?", "Operations Lead"], 3, 3, "field"),
    ("sta-ready-10", "sta-release-governance:v1", [
        ("rg-rollback", "The cited Release Gate rollback condition is: rollback to the prior stable version when post-release verification fails."),
    ], ["Release Gate", "prior stable version"], 2, 0, "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='release-gate' value='Release Gate' vertex='1' parent='1'/></root></mxGraphModel>"),
    ("sta-ready-11", "sta-capacity-planning:v1", [
        ("cp-steady-limit", "The documented steady-state capacity limit is 800 requests per second."),
        ("cp-failover-limit", "The documented failover capacity limit is 1200 requests per second."),
    ], ["Steady State", "800", "Failover", "1200"], 4, 0, None),
    ("sta-ready-12", "sta-incident-command:v1", [
        ("ic-commander", "Incident Commander owns the incident decision lane and assigns the response priority."),
        ("ic-communications", "Communications Lead receives the Incident Commander handoff and owns stakeholder updates."),
    ], ["Incident Commander", "Communications Lead"], 2, 1, "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='escalation-subgraph' value='Selected escalation subgraph' vertex='1' parent='1'/></root></mxGraphModel>"),
]


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    image_dir = OUTPUT / "images"
    image_dir.mkdir(exist_ok=True)
    cohort = json.loads((ROOT / "fixtures" / "stage-a-evidence-decision-cohort-v1.json").read_text())
    requests = {case["caseId"]: case["request"] for case in cohort["cases"]}
    tasks, contexts, anchors = [], [], []
    for case_id, source_version, evidence, labels, vertices, edges, visual in SPECS:
        task_id = case_id.replace("sta-ready", "stagb-dev")
        source, version = source_version.split(":", 1)
        task = {
            "taskId": task_id, "split": "development", "sourceVersion": source_version,
            "selectedMaterialVersion": source_version, "sourceScopeMode": "selected_only",
            "type": "stage_a_grounded_" + case_id.removeprefix("sta-ready-"),
            "request": requests[case_id], "requiredAnchors": [anchor for anchor, _ in evidence],
            "xmlAssertions": {"minVertices": vertices, "minEdges": edges, "requiredLabels": labels},
            "citationAssertions": {"minimumCitations": len(evidence),
                                   "mustCiteAnchors": [anchor for anchor, _ in evidence]},
            "claimAssertions": {"requiredClaims": [
                {"claimId": anchor, "description": text, "requiresCitation": True}
                for anchor, text in evidence
            ]},
        }
        if isinstance(visual, str) and visual.startswith("<mxGraphModel"):
            task["inputXml"] = visual
        task_evidence = []
        for anchor, text in evidence:
            item = {"anchorId": anchor, "sourceVersion": source_version, "page": 1, "text": text}
            if visual in {"warehouse", "field"}:
                image_name = f"{visual}-source.png"
                image_path = image_dir / image_name
                if not image_path.is_file():
                    raise FileNotFoundError(f"missing frozen visual artifact: {image_path}")
                item["imagePath"] = image_path.relative_to(ROOT).as_posix()
                item["imageSha256"] = hashlib.sha256(image_path.read_bytes()).hexdigest()
            task_evidence.append(item)
            anchors.append({"anchorId": anchor, "source": source, "version": version, "page": 1,
                            "text": text})
        tasks.append(task)
        contexts.append({"taskId": task_id, "arm": "fixed", "allowedSourceVersions": [source_version],
                         "evidence": task_evidence})
    (OUTPUT / "tasks.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-a-generation-tasks-v1",
        "purpose": "Frozen E7/E8 generation tasks derived only from Stage A Ready cases.",
        "tasks": tasks,
    }, indent=2) + "\n")
    (OUTPUT / "contexts.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-a-generation-contexts-v1",
        "modelVisibleRequiredEvidence": {"ready": True}, "contexts": contexts,
    }, indent=2) + "\n")
    (OUTPUT / "ground-truth.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-a-generation-ground-truth-v1", "anchors": anchors,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
