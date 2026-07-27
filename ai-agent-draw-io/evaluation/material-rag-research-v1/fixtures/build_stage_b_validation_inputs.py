#!/usr/bin/env python3
"""Build a disjoint synthetic Validation-only generation cohort."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "fixtures" / "generated" / "stage-b-validation"

# These source families, anchors and claims are deliberately absent from Stage B Development.
SPECS = [
    ("stgb-val-01", "sv-release-control:v1", "Draw an approved release control flow.", [("rc-design", "Design Review approves Deployment Package before Release Authorization."), ("rc-audit", "Release Authorization writes the approval event to Control Ledger.")], ["Design Review", "Deployment Package", "Release Authorization", "Control Ledger"], 4, 3, None),
    ("stgb-val-02", "sv-incident-bridge:v1", "Create an incident bridge escalation diagram.", [("ib-lead", "Bridge Lead coordinates the incident bridge and assigns an Investigation Owner."), ("ib-window", "Unacknowledged critical alerts escalate to Bridge Lead within 10 minutes.")], ["Bridge Lead", "Investigation Owner", "10 minutes"], 3, 2, None),
    ("stgb-val-03", "sv-record-disposal:v1", "Update the record lifecycle diagram.", [("rd-disposal", "Evidence Vault retains closed investigation records for five years before Secure Disposal.")], ["Evidence Vault", "5 years", "Secure Disposal"], 3, 2, None),
    ("stgb-val-04", "sv-access-approval:v1", "Draw the privileged access approval path.", [("aa-dual", "Security Reviewer and System Owner must both approve a privileged access request."), ("aa-ticket", "The approval relationship must carry control ticket AC-771.")], ["Security Reviewer", "System Owner", "AC-771"], 2, 1, None),
    ("stgb-val-05", "sv-data-boundary:v1", "Create the data boundary routing diagram.", [("db-route", "External Intake data must pass through Redaction Service before entering Trusted Analytics.")], ["External Intake", "Redaction Service", "Trusted Analytics"], 3, 2, None),
    ("stgb-val-06", "sv-platform-catalog:v1", "Create a service ownership map.", [("pc-owner", "Canonical services are Event Router, Invoice Processor and Compliance Archive, owned by Integration Team, Revenue Team and Risk Team respectively.")], ["Event Router", "Invoice Processor", "Compliance Archive"], 3, 0, None),
    ("stgb-val-07", "sv-change-window:v1", "Draw the maintenance calendar responsibilities.", [("cw-owner", "Infrastructure Change is assigned to Operations Owner and Schema Change to Data Owner."), ("cw-window", "Both changes use the approval window Thu 13:00–15:00.")], ["Operations Owner", "Data Owner", "Thu 13:00–15:00"], 3, 1, None),
    ("stgb-val-08", "sv-procurement-visual:v1", "Reconstruct the selected procurement exception figure as editable draw.io XML.", [("pv-flow", "Request goes to Procurement Review. If Budget available? is Yes, Issue PO then Supplier Confirmation. If No, Finance Escalation then Reprioritize Request.")], ["Request", "Procurement Review", "Budget available?", "Issue PO", "Finance Escalation"], 6, 6, "procurement-source.png"),
    ("stgb-val-09", "sv-maintenance-scan:v1", "Convert the scanned maintenance handoff into editable draw.io XML.", [("ms-flow", "Technician captures readings. If Within tolerance? is Yes, Close work order. If No, Engineering Triage then Schedule follow-up.")], ["Technician", "Capture readings", "Within tolerance?", "Engineering Triage", "Schedule follow-up"], 6, 6, "maintenance-source.png"),
    ("stgb-val-10", "sv-release-recovery:v1", "Add a release recovery condition.", [("rr-rollback", "When smoke verification fails, Release Decision rolls back to the last verified deployment.")], ["Release Decision", "last verified deployment"], 2, 1, None),
    ("stgb-val-11", "sv-capacity-envelope:v1", "Create a capacity comparison diagram.", [("ce-normal", "Normal operation supports 650 events per second."), ("ce-surge", "Surge operation supports 950 events per second.")], ["Normal Operation", "650", "Surge Operation", "950"], 4, 0, None),
    ("stgb-val-12", "sv-escalation-handoff:v1", "Edit the selected escalation subgraph only.", [("eh-coordinator", "Escalation Coordinator owns triage priority and hands the case to Customer Liaison."), ("eh-liaison", "Customer Liaison owns customer updates after the handoff.")], ["Escalation Coordinator", "Customer Liaison"], 2, 1, "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='selected-escalation' value='Selected escalation' vertex='1' parent='1'/></root></mxGraphModel>"),
]


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tasks, contexts, anchors = [], [], []
    for task_id, source_version, request, evidence, labels, vertices, edges, visual in SPECS:
        source, version = source_version.split(":", 1)
        task = {"taskId": task_id, "split": "validation", "sourceVersion": source_version,
                "selectedMaterialVersion": source_version, "sourceScopeMode": "selected_only",
                "type": "stage_b_validation_" + task_id[-2:], "request": request,
                "requiredAnchors": [anchor for anchor, _ in evidence],
                "xmlAssertions": {"minVertices": vertices, "minEdges": edges, "requiredLabels": labels},
                "citationAssertions": {"minimumCitations": len(evidence), "mustCiteAnchors": [anchor for anchor, _ in evidence]},
                "claimAssertions": {"requiredClaims": [{"claimId": anchor, "description": text, "requiresCitation": True} for anchor, text in evidence]}}
        if isinstance(visual, str) and visual.startswith("<mxGraphModel"):
            task["inputXml"] = visual
        items = []
        for anchor, text in evidence:
            item = {"anchorId": anchor, "sourceVersion": source_version, "page": 1, "text": text}
            if visual and not visual.startswith("<mxGraphModel"):
                image = OUTPUT / "images" / visual
                item.update({"imagePath": image.relative_to(ROOT).as_posix(), "imageSha256": hashlib.sha256(image.read_bytes()).hexdigest()})
            items.append(item); anchors.append({"anchorId": anchor, "source": source, "version": version, "page": 1, "text": text})
        tasks.append(task); contexts.append({"taskId": task_id, "arm": "fixed", "allowedSourceVersions": [source_version], "evidence": items})
    (OUTPUT / "tasks.json").write_text(json.dumps({"schemaVersion": "material-rag-stage-b-validation-tasks-v1", "purpose": "Frozen unseen Validation inputs, disjoint from Stage B Development.", "tasks": tasks}, indent=2) + "\n")
    (OUTPUT / "contexts.json").write_text(json.dumps({"schemaVersion": "material-rag-stage-b-validation-contexts-v1", "modelVisibleRequiredEvidence": {"ready": True}, "contexts": contexts}, indent=2) + "\n")
    (OUTPUT / "ground-truth.json").write_text(json.dumps({"schemaVersion": "material-rag-stage-b-validation-ground-truth-v1", "anchors": anchors}, indent=2) + "\n")


if __name__ == "__main__":
    main()
