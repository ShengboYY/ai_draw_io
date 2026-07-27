#!/usr/bin/env python3
"""Build a new local validation cohort after the Stage D acceptance calibration.

The fixture is deliberately new and unseen by the generation model.  Its local authorship means
it is an internal validation cohort, not an externally independent holdout.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "fixtures" / "generated" / "stage-e-postcalibration-validation"


def layout_xml(labels: list[str]) -> str:
    """Supply complete source XML so layout-only tasks may only move existing cells."""
    cells = ["<mxCell id='0'/>", "<mxCell id='1' parent='0'/>"]
    for index, label in enumerate(labels, start=1):
        cells.append(
            f"<mxCell id='layout-{index}' value='{label}' vertex='1' parent='1'>"
            f"<mxGeometry x='{index * 45}' y='45' width='180' height='60' as='geometry'/></mxCell>"
        )
    return "<mxGraphModel><root>" + "".join(cells) + "</root></mxGraphModel>"


# id, family, source, request, evidence, labels, vertices, edges, image file
SPECS = [
    ("stge-val-01", "material_grounded_creation", "pe-release-governance:v1",
     "Create an editable governed release flow.",
     [("rg-route", "Release Coordinator sends a Signed Build to Change Approval before Authorized Deployment."),
      ("rg-owner", "Authorized Deployment is owned by Platform Operations.")],
     ["Release Coordinator", "Signed Build", "Change Approval", "Authorized Deployment", "Platform Operations"], 4, 3, None),
    ("stge-val-02", "material_grounded_creation", "pe-evidence-retention:v1",
     "Draw the approved evidence retention lifecycle.",
     [("er-retain", "Case Archive retains closed evidence for 90 days before Review Archive."),
      ("er-dispose", "Review Archive sends expired evidence to Certified Disposal.")],
     ["Case Archive", "90 days", "Review Archive", "Certified Disposal"], 3, 2, None),
    ("stge-val-03", "material_grounded_creation", "pe-customer-change:v1",
     "Create a customer-impact change approval diagram.",
     [("cc-impact", "Change Request is assessed by Impact Review before Customer Notice."),
      ("cc-approval", "Customer Notice may be issued only after Change Authority approval.")],
     ["Change Request", "Impact Review", "Change Authority", "Customer Notice"], 4, 3, None),
    ("stge-val-04", "structural_edit", "pe-oncall-handoff-edit:v1",
     "Update the selected on-call handoff subgraph from the approved material.",
     [("oh-handoff", "Primary Responder hands an acknowledged incident to the Secondary Responder."),
      ("oh-note", "Secondary Responder records the handoff note before shift close.")],
     ["Primary Responder", "Secondary Responder", "handoff note"], 3, 2, None),
    ("stge-val-05", "structural_edit", "pe-billing-exception-edit:v1",
     "Edit the selected billing-exception branch using the selected source.",
     [("be-route", "Billing Exception goes to Revenue Review before Account Correction."),
      ("be-owner", "Account Correction is owned by Revenue Operations.")],
     ["Billing Exception", "Revenue Review", "Account Correction", "Revenue Operations"], 3, 2, None),
    ("stge-val-06", "layout_only_edit", "pe-layout-roles:v1",
     "Separate the existing process roles for readability without changing their content or style.",
     [("lr-roles", "Submitter, Approver and Observer are the existing process roles.")],
     ["Submitter", "Approver", "Observer"], 3, 0, None),
    ("stge-val-07", "layout_only_edit", "pe-layout-states:v1",
     "Improve spacing of the existing states without changing their content or style.",
     [("ls-states", "Pending, Validated, and Archived are the existing lifecycle states.")],
     ["Pending", "Validated", "Archived"], 3, 0, None),
    ("stge-val-08", "version_and_authorization_safe_edit", "pe-catalog-approval:v4",
     "Draw the selected, authorized catalog approval route.",
     [("ca-v4", "Catalog Analyst submits Version 4 to Product Approver before Catalog Publish."),
      ("ca-scope", "Only Version 4 is authorized for this edit.")],
     ["Catalog Analyst", "Version 4", "Product Approver", "Catalog Publish"], 4, 3, None),
    ("stge-val-09", "failure_and_recovery", "pe-retrieval-recovery:v1",
     "Create the safe source-retrieval recovery diagram.",
     [("rr-block", "When Evidence Retrieval is Unavailable, source-grounded generation is blocked while Manual Diagramming remains available."),
      ("rr-resume", "Retry Retrieval may continue only after Evidence Retrieval is restored.")],
     ["Evidence Retrieval Unavailable", "Manual Diagramming", "Retry Retrieval", "Evidence Retrieval restored"], 4, 3, None),
    ("stge-val-10", "visual_or_ocr_to_editable_xml", "pe-evidence-completeness-visual:v1",
     "Reconstruct the selected evidence-completeness figure as editable draw.io XML.",
     [("ec-flow", "Field Request goes to Evidence Check. If Evidence complete? is Yes, Approve Request then Issue Work Order. If No, Request Missing Evidence then Await Resubmission.")],
     ["Field Request", "Evidence Check", "Evidence complete?", "Approve Request", "Issue Work Order", "Request Missing Evidence", "Await Resubmission", "Yes", "No"], 7, 6, "evidence-completeness-source.png"),
    ("stge-val-11", "visual_or_ocr_to_editable_xml", "pe-service-recovery-visual:v1",
     "Convert the selected service-recovery figure into editable draw.io XML.",
     [("sr-flow", "Alert Received goes to Assess Service. If Service restored? is Yes, Record Resolution. If No, Start Recovery then Notify Incident Lead then Reassess Service.")],
     ["Alert Received", "Assess Service", "Service restored?", "Record Resolution", "Start Recovery", "Notify Incident Lead", "Reassess Service", "Yes", "No"], 7, 6, "service-recovery-source.png"),
    ("stge-val-12", "failure_and_recovery", "pe-save-recovery:v1",
     "Draw the safe diagram-save recovery path.",
     [("ss-preserve", "Diagram Save Failed preserves the editable canvas and offers Retry Save."),
      ("ss-escalate", "After a repeated save failure, the agent routes the case to Storage Support.")],
     ["Diagram Save Failed", "Retry Save", "editable canvas", "Storage Support"], 4, 3, None),
]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tasks, contexts, anchors = [], [], []
    for task_id, family, source_version, request, evidence, labels, vertices, edges, image_name in SPECS:
        source, version = source_version.split(":", 1)
        task = {
            "taskId": task_id, "split": "validation", "sourceVersion": source_version,
            "selectedMaterialVersion": source_version, "sourceScopeMode": "selected_only",
            "type": family, "request": request, "requiredAnchors": [anchor for anchor, _ in evidence],
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
        "schemaVersion": "material-rag-stage-e-postcalibration-validation-tasks-v1",
        "purpose": "New local validation inputs created after Stage D calibration; unseen by the generation model and not an independent external holdout.",
        "tasks": tasks,
    }, indent=2) + "\n")
    (OUTPUT / "contexts.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-e-postcalibration-validation-contexts-v1",
        "modelVisibleRequiredEvidence": {"ready": True}, "contexts": contexts,
    }, indent=2) + "\n")
    (OUTPUT / "ground-truth.json").write_text(json.dumps({
        "schemaVersion": "material-rag-stage-e-postcalibration-validation-ground-truth-v1", "anchors": anchors,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
