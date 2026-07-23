#!/usr/bin/env python3
"""Build a locally authored, once-only internal release-style Draw.io cohort.

This is intentionally not called an independent final holdout: its sources and answers live in
the development repository at the user's direction. It is still isolated from prior model runs.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "fixtures" / "generated" / "stage-d-internal-release"


def edit_xml(cell_id: str, label: str) -> str:
    return ("<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
            f"<mxCell id='{cell_id}' value='{label}' vertex='1' parent='1'>"
            "<mxGeometry x='40' y='40' width='180' height='60' as='geometry'/></mxCell>"
            "</root></mxGraphModel>")


def layout_xml(labels: list[str]) -> str:
    """Provide the full factual diagram so geometry-only evaluation can prohibit rewrites."""
    cells = ["<mxCell id='0'/>", "<mxCell id='1' parent='0'/>"]
    for index, label in enumerate(labels, start=1):
        cells.append(
            f"<mxCell id='layout-{index}' value='{label}' vertex='1' parent='1'>"
            f"<mxGeometry x='{index * 40}' y='40' width='180' height='60' as='geometry'/></mxCell>"
        )
    return "<mxGraphModel><root>" + "".join(cells) + "</root></mxGraphModel>"


# id, family, source version, request, frozen evidence, required labels, vertices, edges, input XML, image
SPECS = [
    ("stgd-int-01", "material_grounded_creation", "ir-customer-onboarding:v1", "Create an editable customer onboarding flow.",
     [("co-intake", "Customer Intake is followed by Identity Check before Account Activation."), ("co-owner", "Account Activation is owned by Customer Operations.")],
     ["Customer Intake", "Identity Check", "Account Activation", "Customer Operations"], 4, 3, None, None),
    ("stgd-int-02", "material_grounded_creation", "ir-data-retention:v1", "Draw the approved data retention lifecycle.",
     [("dr-store", "Raw Archive retains approved records for 30 days before Curated Archive."), ("dr-delete", "Curated Archive sends expired records to Verified Deletion.")],
     ["Raw Archive", "30 days", "Curated Archive", "Verified Deletion"], 4, 3, None, None),
    ("stgd-int-03", "material_grounded_creation", "ir-release-ownership:v1", "Create the release ownership map.",
     [("ro-build", "Build Steward owns the signed package."), ("ro-approve", "Release Manager approves the signed package before Production Gateway.")],
     ["Build Steward", "Release Manager", "Production Gateway"], 3, 2, None, None),
    ("stgd-int-04", "material_grounded_creation", "ir-fraud-route:v1", "Create a payment review decision flow.",
     [("fr-route", "Payment Intake goes to Risk Screening. A flagged payment goes to Analyst Review; an unflagged payment goes to Settlement Queue.")],
     ["Payment Intake", "Risk Screening", "Analyst Review", "Settlement Queue"], 4, 3, None, None),
    ("stgd-int-05", "material_grounded_creation", "ir-vendor-access:v1", "Draw the vendor access approval route.",
     [("va-sponsor", "Vendor Sponsor submits the access request to Security Validation."), ("va-expiry", "Approved vendor access expires after 14 days.")],
     ["Vendor Sponsor", "Security Validation", "14 days"], 3, 2, None, None),
    ("stgd-int-06", "structural_edit", "ir-customer-onboarding-edit:v1", "Update the selected onboarding step using the approved source.",
     [("ce-verify", "Identity Check hands verified customers to Account Activation.")],
     ["Identity Check", "Account Activation"], 2, 1, edit_xml("selected-onboarding", "Identity Check"), None),
    ("stgd-int-07", "structural_edit", "ir-incident-routing-edit:v1", "Edit the selected incident escalation subgraph.",
     [("ie-escalate", "Service Owner escalates unresolved incidents to Incident Commander."), ("ie-update", "Incident Commander assigns Communications Lead.")],
     ["Service Owner", "Incident Commander", "Communications Lead"], 3, 2, edit_xml("selected-incident", "Service Owner"), None),
    ("stgd-int-08", "structural_edit", "ir-invoice-reconcile-edit:v1", "Update the selected reconciliation branch.",
     [("ir-match", "Invoice Match routes mismatches to Finance Investigation before Supplier Query.")],
     ["Invoice Match", "Finance Investigation", "Supplier Query"], 3, 2, edit_xml("selected-invoice", "Invoice Match"), None),
    ("stgd-int-09", "layout_only_edit", "ir-layout-lanes:v1", "Arrange the existing roles as two clear swimlanes without changing their factual labels.",
     [("ll-roles", "Requester and Reviewer are the two existing process roles.")],
     ["Requester", "Reviewer"], 2, 0, edit_xml("requester", "Requester"), None),
    ("stgd-int-10", "layout_only_edit", "ir-layout-sequence:v1", "Improve spacing of the existing approval sequence without changing its labels.",
     [("ls-roles", "Draft, Review and Publish are the existing approval sequence labels.")],
     ["Draft", "Review", "Publish"], 3, 0, edit_xml("draft", "Draft"), None),
    ("stgd-int-11", "layout_only_edit", "ir-layout-status:v1", "Make the existing status diagram easier to read without changing its facts.",
     [("lt-status", "Queued, Running and Complete are the existing status labels.")],
     ["Queued", "Running", "Complete"], 3, 0, edit_xml("queued", "Queued"), None),
    ("stgd-int-12", "visual_or_ocr_to_editable_xml", "ir-telemetry-visual:v1", "Reconstruct the selected telemetry calibration figure as editable draw.io XML.",
     [("tv-flow", "Sensor Intake goes to Calibration Check. If Reading valid? is Yes, Publish Telemetry then Operations Dashboard. If No, Quarantine Sample then Lab Review.")],
     ["Sensor Intake", "Calibration Check", "Reading valid?", "Publish Telemetry", "Quarantine Sample", "Lab Review"], 7, 7, None, "telemetry-calibration-source.png"),
    ("stgd-int-13", "visual_or_ocr_to_editable_xml", "ir-site-safety-scan:v1", "Convert the scanned site-safety handoff into editable draw.io XML.",
     [("ss-flow", "Field Technician attaches Site Photo. If Safety cleared? is Yes, Close Visit. If No, Dispatch Supervisor then Arrange Return.")],
     ["Field Technician", "Attach Site Photo", "Safety cleared?", "Close Visit", "Dispatch Supervisor", "Arrange Return"], 7, 7, None, "site-safety-source.png"),
    ("stgd-int-14", "version_and_authorization_safe_edit", "ir-price-book:v2", "Draw the currently authorized price-book approval flow.",
     [("pb-v2", "Price Analyst submits Version 2 to Commercial Approver before Catalog Publish.")],
     ["Price Analyst", "Version 2", "Commercial Approver", "Catalog Publish"], 4, 3, None, None),
    ("stgd-int-15", "version_and_authorization_safe_edit", "ir-policy-exception:v3", "Create the authorized policy-exception routing diagram.",
     [("pe-auth", "Policy Owner approves exception EX-903 before Compliance Register records it.")],
     ["Policy Owner", "EX-903", "Compliance Register"], 3, 2, None, None),
    ("stgd-int-16", "version_and_authorization_safe_edit", "ir-contract-change:v2", "Update the selected contract-change approval route from the selected version.",
     [("cc-v2", "Legal Reviewer and Procurement Lead must approve Change Set B before Supplier Notice.")],
     ["Legal Reviewer", "Procurement Lead", "Change Set B", "Supplier Notice"], 4, 3, edit_xml("selected-contract", "Change Set B"), None),
    ("stgd-int-17", "failure_and_recovery", "ir-search-recovery:v1", "Draw the safe material-search recovery path.",
     [("sr-safe", "During Vector Search Unavailable, material-backed drawing is blocked while Manual Drawing remains available."), ("sr-retry", "Retry Retrieval may continue only after Vector Search is restored.")],
     ["Vector Search Unavailable", "Manual Drawing", "Retry Retrieval"], 3, 2, None, None),
    ("stgd-int-18", "failure_and_recovery", "ir-save-recovery:v1", "Create the safe canvas-save recovery diagram.",
     [("cr-save", "Canvas Save Failed must preserve the current canvas and offer Retry Save."), ("cr-escalate", "A repeated save failure is escalated to Storage Support.")],
     ["Canvas Save Failed", "Retry Save", "Storage Support"], 3, 2, None, None),
    ("stgd-int-19", "failure_and_recovery", "ir-ocr-recovery:v1", "Draw the OCR recovery route for a scanned source.",
     [("or-ocr", "OCR Unavailable blocks source-grounded extraction and offers Retry OCR."), ("or-manual", "After a completed OCR failure, the user may provide a manual source excerpt.")],
     ["OCR Unavailable", "Retry OCR", "manual source excerpt"], 3, 2, None, None),
    ("stgd-int-20", "failure_and_recovery", "ir-blob-recovery:v1", "Create the evidence blob recovery flow.",
     [("br-blob", "Evidence Blob Unavailable blocks cited material generation until Blob Read is restored."), ("br-audit", "The recovery event is recorded in Retrieval Audit.")],
     ["Evidence Blob Unavailable", "Blob Read", "Retrieval Audit"], 3, 2, None, None),
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tasks, contexts, anchors = [], [], []
    for task_id, family, source_version, request, evidence, labels, vertices, edges, input_xml, image_name in SPECS:
        source, version = source_version.split(":", 1)
        task = {
            "taskId": task_id, "split": "holdout", "sourceVersion": source_version,
            "selectedMaterialVersion": source_version, "sourceScopeMode": "selected_only",
            "type": family, "request": request, "requiredAnchors": [anchor for anchor, _ in evidence],
            "xmlAssertions": {"minVertices": vertices, "minEdges": edges, "requiredLabels": labels},
            "citationAssertions": {"minimumCitations": len(evidence), "mustCiteAnchors": [anchor for anchor, _ in evidence]},
            "claimAssertions": {"requiredClaims": [
                {"claimId": anchor, "description": text, "requiresCitation": True} for anchor, text in evidence
            ]},
        }
        if family == "layout_only_edit":
            input_xml = layout_xml(labels)
            task["editAssertions"] = {"geometryOnly": True}
        if input_xml:
            task["inputXml"] = input_xml
        visible = []
        for anchor, text in evidence:
            item = {"anchorId": anchor, "sourceVersion": source_version, "page": 1, "text": text}
            if image_name:
                image = OUTPUT / "images" / image_name
                item.update({"imagePath": image.relative_to(ROOT).as_posix(), "imageSha256": sha256(image)})
            visible.append(item)
            anchors.append({"anchorId": anchor, "source": source, "version": version, "page": 1, "text": text})
        tasks.append(task)
        contexts.append({"taskId": task_id, "arm": "fixed", "allowedSourceVersions": [source_version], "evidence": visible})
    (OUTPUT / "tasks.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-d-internal-release-tasks-v1",
        "purpose": "Locally authored once-only internal release-style cohort; not an independent final holdout.",
        "tasks": tasks,
    }, indent=2) + "\n")
    (OUTPUT / "contexts.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-d-internal-release-contexts-v1",
        "modelVisibleRequiredEvidence": {"ready": True}, "contexts": contexts,
    }, indent=2) + "\n")
    (OUTPUT / "ground-truth.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-d-internal-release-ground-truth-v1", "anchors": anchors,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
