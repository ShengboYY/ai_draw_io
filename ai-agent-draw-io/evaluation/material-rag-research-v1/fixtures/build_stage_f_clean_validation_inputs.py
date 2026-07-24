#!/usr/bin/env python3
"""Build a clean, unseen internal Draw.io validation cohort after Stage E diagnostics."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "fixtures" / "generated" / "stage-f-clean-validation"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def layout_xml(labels: list[str]) -> str:
    """Provide complete source XML so layout-only tasks cannot change labels or styles."""
    cells = ["<mxCell id='0'/>", "<mxCell id='1' parent='0'/>"]
    for index, label in enumerate(labels, start=1):
        cells.append(
            f"<mxCell id='layout-{index}' value='{label}' style='rounded=1;fillColor=#f8fafc;' "
            f"vertex='1' parent='1'><mxGeometry x='{index * 45}' y='45' width='180' height='60' as='geometry'/></mxCell>"
        )
    return "<mxGraphModel><root>" + "".join(cells) + "</root></mxGraphModel>"


# id, family, source version, request, evidence, visible labels, minimum vertices/edges, image
SPECS = [
    ("stgf-val-01", "material_grounded_creation", "sf-incident-briefing:v1",
     "Create an editable incident-briefing flow.",
     [("ib-route", "Incident Commander sends a customer-impact summary to Executive Review before the recovery decision is published."),
      ("ib-owner", "The recovery decision names the accountable Operations Lead.")],
     ["Incident Commander", "Customer-impact Summary", "Executive Review", "Recovery Decision", "Operations Lead"], 4, 3, None),
    ("stgf-val-02", "material_grounded_creation", "sf-release-exception:v1",
     "Draw the approved release-exception route.",
     [("re-assess", "Release Exception goes to Risk Assessment before Change Owner approval."),
      ("re-record", "Approved Release Exception is recorded in the Deployment Register.")],
     ["Release Exception", "Risk Assessment", "Change Owner", "Deployment Register"], 4, 3, None),
    ("stgf-val-03", "structural_edit", "sf-vendor-onboarding-edit:v1",
     "Update the selected vendor-onboarding subgraph from the approved material.",
     [("vo-screen", "Vendor Intake goes to Security Screening before Contract Setup."),
      ("vo-owner", "Contract Setup is owned by Procurement Operations.")],
     ["Vendor Intake", "Security Screening", "Contract Setup", "Procurement Operations"], 3, 2, None),
    ("stgf-val-04", "structural_edit", "sf-access-review-edit:v1",
     "Edit the selected access-review branch using the selected source.",
     [("ar-review", "Access Request goes to Entitlement Review before Manager Approval."),
      ("ar-close", "Manager Approval creates an Access Record for the requester.")],
     ["Access Request", "Entitlement Review", "Manager Approval", "Access Record"], 4, 3, None),
    ("stgf-val-05", "layout_only_edit", "sf-layout-escalation:v1",
     "Separate the existing escalation roles for readability without changing their content or style.",
     [("le-roles", "Duty Manager, Incident Lead and Communications Owner are the existing escalation roles.")],
     ["Duty Manager", "Incident Lead", "Communications Owner"], 3, 0, None),
    ("stgf-val-06", "layout_only_edit", "sf-layout-evidence:v1",
     "Improve spacing of the existing evidence states without changing their content or style.",
     [("lv-states", "Captured, Verified and Retained are the existing evidence states.")],
     ["Captured", "Verified", "Retained"], 3, 0, None),
    ("stgf-val-07", "version_and_authorization_safe_edit", "sf-catalog-approval:v7",
     "Draw the selected, authorized catalog approval route.",
     [("ca-route", "Catalog Analyst submits Version 7 to Product Steward before Catalog Publish."),
      ("ca-scope", "Only Version 7 is authorized for this edit.")],
     ["Catalog Analyst", "Version 7", "Product Steward", "Catalog Publish"], 4, 3, None),
    ("stgf-val-08", "failure_and_recovery", "sf-evidence-sync-recovery:v1",
     "Create the safe evidence-sync recovery diagram.",
     [("es-block", "When Evidence Sync is Unavailable, grounded publishing is blocked while Manual Notes remain available."),
      ("es-resume", "Retry Sync may continue only after Evidence Sync is restored.")],
     ["Evidence Sync Unavailable", "Manual Notes", "Retry Sync", "Evidence Sync restored"], 4, 3, None),
    ("stgf-val-09", "failure_and_recovery", "sf-canvas-save-recovery:v1",
     "Draw the safe canvas-save recovery path.",
     [("cs-preserve", "Canvas Save Failed preserves the editable diagram and offers Retry Save."),
      ("cs-escalate", "After repeated save failure, the case is routed to Workspace Support.")],
     ["Canvas Save Failed", "Retry Save", "editable diagram", "Workspace Support"], 4, 3, None),
    ("stgf-val-10", "visual_or_ocr_to_editable_xml", "sf-change-closure-visual:v1",
     "Reconstruct the selected change-closure figure as editable draw.io XML.",
     [("cc-flow", "Change Implemented goes to Verify Outcome. If Outcome accepted? is Yes, Record Closure then Publish Change Note. If No, Plan Remediation then Recheck Outcome.")],
     ["Change Implemented", "Verify Outcome", "Outcome accepted?", "Record Closure", "Publish Change Note", "Plan Remediation", "Recheck Outcome", "Yes", "No"], 7, 6, "change-closure-source.png"),
    ("stgf-val-11", "visual_or_ocr_to_editable_xml", "sf-privacy-escalation-visual:v1",
     "Convert the selected privacy-escalation figure into editable draw.io XML.",
     [("pe-flow", "Privacy Alert goes to Assess Exposure. If External notice required? is Yes, Notify Privacy Lead then Send Notice. If No, Document Assessment then Monitor Follow-up.")],
     ["Privacy Alert", "Assess Exposure", "External notice required?", "Notify Privacy Lead", "Send Notice", "Document Assessment", "Monitor Follow-up", "Yes", "No"], 7, 6, "privacy-escalation-source.png"),
    ("stgf-val-12", "material_grounded_creation", "sf-service-restoration:v1",
     "Create an editable service-restoration flow.",
     [("sr-diagnose", "Service Triage sends an affected component to Restoration Work before Service Verification."),
      ("sr-close", "Service Verification records the restoration result in the Incident Timeline.")],
     ["Service Triage", "Affected Component", "Restoration Work", "Service Verification", "Incident Timeline"], 4, 4, None),
]


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tasks, contexts, anchors = [], [], []
    for task_id, family, source_version, request, evidence, labels, vertices, edges, image_name in SPECS:
        source, version = source_version.split(":", 1)
        task = {
            "taskId": task_id, "split": "validation", "sourceVersion": source_version,
            "selectedMaterialVersion": source_version, "sourceScopeMode": "selected_only",
            "type": family, "request": request,
            "requiredAnchors": [anchor for anchor, _ in evidence],
            "xmlAssertions": {"minVertices": vertices, "minEdges": edges, "requiredLabels": labels},
            "citationAssertions": {"minimumCitations": len(evidence), "mustCiteAnchors": [anchor for anchor, _ in evidence]},
            "claimAssertions": {"requiredClaims": [
                {"claimId": anchor, "description": text, "requiresCitation": True} for anchor, text in evidence
            ]},
        }
        if family == "layout_only_edit":
            task["inputXml"] = layout_xml(labels)
            task["editAssertions"] = {"geometryOnly": True}
        elif family == "structural_edit":
            task["inputXml"] = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='selected' value='Selected step' vertex='1' parent='1'><mxGeometry x='40' y='40' width='180' height='60' as='geometry'/></mxCell></root></mxGraphModel>"
        visible = []
        for anchor, text in evidence:
            item = {"anchorId": anchor, "sourceVersion": source_version, "page": 1, "text": text}
            if image_name:
                image = OUTPUT / "images" / image_name
                item.update({"imagePath": image.relative_to(ROOT).as_posix(), "imageSha256": digest(image)})
            visible.append(item)
            anchors.append({"anchorId": anchor, "source": source, "version": version, "page": 1, "text": text})
        tasks.append(task)
        contexts.append({"taskId": task_id, "arm": "fixed", "allowedSourceVersions": [source_version], "evidence": visible})
    (OUTPUT / "tasks.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-f-clean-validation-tasks-v1",
        "purpose": "New local internal validation inputs after Stage E duplicate-run diagnostics; unseen by the generation model and not an external independent holdout.",
        "tasks": tasks,
    }, indent=2) + "\n")
    (OUTPUT / "contexts.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-f-clean-validation-contexts-v1",
        "modelVisibleRequiredEvidence": {"ready": True}, "contexts": contexts,
    }, indent=2) + "\n")
    (OUTPUT / "ground-truth.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-f-clean-validation-ground-truth-v1", "anchors": anchors,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
