#!/usr/bin/env python3
"""Build the expanded draw.io generation task fixture without rewriting v2 history."""

from __future__ import annotations

import copy
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
V2 = ROOT / "drawio-generation-tasks-v2.json"
V3 = ROOT / "drawio-generation-tasks-v3.json"


def creation_task(task_id: str, split: str, source: str, task_type: str,
                  request: str, anchors: list[str], labels: list[str],
                  claim: str, *, source_scope_mode: str = "chartbook_auto",
                  min_edges: int | None = None) -> dict:
    """Create one evidence-grounded task with structural and citation assertions."""
    xml_assertions = {
        "minVertices": len(labels),
        "requiredLabels": labels,
    }
    if min_edges is not None:
        xml_assertions["minEdges"] = min_edges
    task = {
        "taskId": task_id,
        "split": split,
        "sourceVersion": source,
        "sourceScopeMode": source_scope_mode,
        "type": task_type,
        "request": request,
        "requiredAnchors": anchors,
        "xmlAssertions": xml_assertions,
        "citationAssertions": {
            "minimumCitations": len(anchors),
            "mustCiteAnchors": anchors,
        },
        "claimAssertions": {
            "requiredClaims": [{
                "claimId": f"{task_id}-claim",
                "description": claim,
                "requiresCitation": True,
            }]
        },
    }
    if source_scope_mode == "selected_only":
        task["selectedMaterialVersion"] = source
    return task


def build() -> dict:
    """Return the v3 fixture with twenty Development and twenty Validation tasks."""
    fixture = json.loads(V2.read_text(encoding="utf-8"))
    fixture["schemaVersion"] = "material-rag-drawio-generation-tasks-v3"
    fixture["purpose"] = (
        "Active draw.io-agent Development and Validation tasks spanning creation, "
        "structural editing, visual/OCR reconstruction, source scoping, version safety, "
        "failure-state rendering and citation-grounded policy diagrams. v2 remains historical."
    )
    fixture["developmentChartbookSourceVersions"] = [
        "drawio-agent-architecture:v1",
        "drawio-workflow-handbook:v1",
        "drawio-planning-workshop-scan:v1",
        "expansion-datacenter-change:v1",
        "scenario-payment-settlement:v1",
        "expansion-platform-resilience:v1",
        "expansion-field-audit-scan:v1",
    ]
    fixture["validationChartbookSourceVersions"] = [
        "drawio-collaboration-governance:v1",
        "scenario-ota-rollout:v1",
        "expansion-observability:v1",
        "expansion-material-governance:v1",
        "realistic-solar-manual:v1",
    ]

    # Keep the six reviewed v2 Development tasks and three v2 Validation tasks as the seed.
    development = [copy.deepcopy(task) for task in fixture["tasks"] if task["split"] == "development"]
    validation = [copy.deepcopy(task) for task in fixture["tasks"] if task["split"] == "validation"]
    for task in validation:
        task["sourceScopeMode"] = "chartbook_auto"

    development.extend([
        creation_task(
            "dgt-dev-07", "development", "drawio-workflow-handbook:v1",
            "material_grounded_creation",
            "Create an editable evidence-to-draft workflow from the handbook.",
            ["dwh-route-bundle", "dwh-route-draft"],
            ["EVIDENCE BUNDLE", "TYPED PLAN", "LAYOUT CHECK", "CANVAS DRAFT"],
            "The workflow runs from EVIDENCE BUNDLE through TYPED PLAN and LAYOUT CHECK to CANVAS DRAFT.",
            min_edges=3,
        ),
        creation_task(
            "dgt-dev-08", "development", "drawio-workflow-handbook:v1",
            "policy_diagram",
            "Create a decision diagram contrasting an explicitly selected material with automatic wider search.",
            ["dwh-explicit", "dwh-auto-scope"],
            ["Explicit material", "Search selected scope only", "Automatic selection", "Wider authorized search"],
            "Explicit material restricts retrieval to that scope; wider authorized search requires automatic selection.",
            min_edges=2,
        ),
        creation_task(
            "dgt-dev-09", "development", "drawio-agent-architecture:v1",
            "material_grounded_creation",
            "Create an editable funnel showing the candidate, hydration, and final evidence budgets.",
            ["daa-candidates", "daa-hydration", "daa-bundle"],
            ["40 candidates", "16 hydrated", "8 final evidence items"],
            "The baseline retrieves 40 candidates, hydrates at most 16, and includes at most 8 final evidence items.",
            min_edges=2,
        ),
        creation_task(
            "dgt-dev-10", "development", "drawio-planning-workshop-scan:v1",
            "scan_to_editable_xml",
            "Turn the scanned visual-verification checklist into an editable draw.io checklist.",
            ["dpw-visual-scan", "dpw-editable-scan"],
            ["Pixel verification", "Arrow direction", "Containment", "Raster table cells",
             "Stable cell identifiers", "Valid draw.io XML"],
            "Scanned arrow direction, containment and raster cells require pixel verification, and the result needs stable IDs and valid XML.",
            min_edges=1,
        ),
        creation_task(
            "dgt-dev-11", "development", "drawio-planning-workshop-scan:v1",
            "policy_diagram",
            "Create a source-selection and degraded-search decision diagram from the scanned workshop notes.",
            ["dpw-source-order", "dpw-removed", "dpw-degraded"],
            ["Explicit source first", "Removed source blocked", "DEGRADED SEARCH", "Not NO EVIDENCE"],
            "Explicit sources come first, removed sources cannot support new requests, and search interruption is degraded search rather than no evidence.",
            min_edges=2,
        ),
        creation_task(
            "dgt-dev-12", "development", "expansion-datacenter-change:v1",
            "material_grounded_creation",
            "Recreate the datacenter capacity-change route as editable draw.io XML.",
            ["dcc-arch-route", "dcc-arch-apply"],
            ["CAPACITY CHECK", "CHANGE ORCHESTRATE", "APPLY"],
            "The route is CAPACITY CHECK to CHANGE ORCHESTRATE to APPLY.",
            min_edges=2,
        ),
        creation_task(
            "dgt-dev-13", "development", "expansion-datacenter-change:v1",
            "threshold_diagram",
            "Create a threshold gate for escalation to human review.",
            ["dcc-threshold"],
            ["Utilization > 85%", "Three consecutive samples", "Human review"],
            "Human review starts only after utilization exceeds 85% for three consecutive samples.",
            min_edges=2,
        ),
        creation_task(
            "dgt-dev-14", "development", "scenario-payment-settlement:v1",
            "material_grounded_creation",
            "Create the editable payment request route from acquisition through settlement.",
            ["pss-route-acquire", "pss-arch-route", "pss-route-settle"],
            ["ACQUIRE", "RISK DECISION", "FX CONVERT", "SETTLE"],
            "The request route is ACQUIRE to RISK DECISION to FX CONVERT to SETTLE.",
            min_edges=3,
        ),
        creation_task(
            "dgt-dev-15", "development", "scenario-payment-settlement:v1",
            "sequence_to_editable_xml",
            "Create the editable settlement sequence from clearing through notification.",
            ["pss-settle-clearing", "pss-settle-sequence", "pss-settle-notify"],
            ["CLEARING", "BANK ACK", "RECONCILE", "NOTIFY"],
            "The settlement sequence is CLEARING to BANK ACK to RECONCILE to NOTIFY.",
            min_edges=3,
        ),
        creation_task(
            "dgt-dev-16", "development", "expansion-platform-resilience:v1",
            "failure_state_diagram",
            "Create an editable failure-response matrix for vector search, object storage, and canvas save.",
            ["pre-vector", "pre-objstore", "pre-canvas"],
            ["Vector search", "Manual drawing available", "Material search blocked",
             "Object store", "Canvas save", "SEV-1"],
            "Vector failure leaves manual drawing available but blocks material search; object-store and canvas-save failures are separately represented, with canvas save as SEV-1.",
        ),
        creation_task(
            "dgt-dev-17", "development", "expansion-platform-resilience:v1",
            "severity_legend",
            "Create an editable SEV-1, SEV-2, and SEV-3 incident legend.",
            ["pre-severity", "pre-sev2", "pre-sev3"],
            ["SEV-1", "Cross-scope disclosure", "SEV-2", "Cannot complete without data loss",
             "SEV-3", "Retryable delay", "Work preserved"],
            "SEV-1 covers possible cross-scope disclosure, SEV-2 covers tasks that cannot complete without data loss, and SEV-3 is a retryable delay with preserved work.",
        ),
        creation_task(
            "dgt-dev-18", "development", "expansion-field-audit-scan:v1",
            "scan_to_editable_xml",
            "Convert the scanned B-16 audit finding into an editable threshold-and-status diagram.",
            ["fas-depth", "fas-threshold", "fas-row-status"],
            ["B-16", "27 mm", "+6 mm", "25 mm threshold", "ENGINEERING REVIEW"],
            "B-16 measured 27 mm with +6 mm growth, exceeding the 25 mm or +5 mm engineering-review threshold.",
            min_edges=2,
        ),
        creation_task(
            "dgt-dev-19", "development", "drawio-agent-architecture:v1",
            "version_policy_diagram",
            "Create a version-selection diagram contrasting a new request with reopening an existing diagram.",
            ["daa-latest", "daa-version-pin"],
            ["New request", "Latest ready version", "Reopen existing diagram", "Remain pinned to V1"],
            "New requests default to the latest ready version, while reopened evidence remains pinned to V1.",
            min_edges=2,
        ),
        creation_task(
            "dgt-dev-20", "development", "drawio-agent-architecture:v1",
            "selected_source_policy",
            "Create a two-node policy diagram showing that explicit source selection wins.",
            ["daa-explicit-source"],
            ["Explicit source selected", "Selected source wins"],
            "Explicit source selection wins.",
            source_scope_mode="selected_only",
            min_edges=1,
        ),
    ])

    validation.extend([
        creation_task(
            "dgt-val-04", "validation", "scenario-ota-rollout:v1",
            "material_grounded_creation",
            "Create the editable OTA release route from repository to vehicle.",
            ["ota-route-repo", "ota-arch-route", "ota-route-vehicle"],
            ["VERSION REPO", "CANARY CTRL", "DISTRIBUTE", "VEHICLE"],
            "The OTA route is VERSION REPO to CANARY CTRL to DISTRIBUTE to VEHICLE.",
            min_edges=3,
        ),
        creation_task(
            "dgt-val-05", "validation", "scenario-ota-rollout:v1",
            "sequence_to_editable_xml",
            "Create the editable OTA canary sequence.",
            ["ota-canary-batch", "ota-canary-sequence", "ota-canary-complete"],
            ["BATCH START", "HEALTH CHECK", "EXPAND", "COMPLETE"],
            "The canary sequence is BATCH START to HEALTH CHECK to EXPAND to COMPLETE.",
            min_edges=3,
        ),
        creation_task(
            "dgt-val-06", "validation", "scenario-ota-rollout:v1",
            "version_policy_diagram",
            "Create a version-safe OTA diagram showing the rule for existing evidence.",
            ["ota-version-pin"],
            ["Existing evidence", "Original version remains pinned"],
            "Existing OTA evidence remains pinned to its original version.",
            min_edges=1,
        ),
        creation_task(
            "dgt-val-07", "validation", "scenario-ota-rollout:v1",
            "role_policy_diagram",
            "Create an approval-role diagram for OTA rollout and rollback advice.",
            ["ota-approve-rollout"],
            ["Quality reviewer", "Rollout advice", "Rollback advice", "Cannot approve alone"],
            "A quality reviewer may advise rollout or rollback but cannot approve alone.",
        ),
        creation_task(
            "dgt-val-08", "validation", "expansion-observability:v1",
            "material_grounded_creation",
            "Recreate the observability alert route as editable draw.io XML.",
            ["mso-arch-route", "mso-arch-notify"],
            ["ALERT EVAL", "ROUTE DISPATCH", "NOTIFY"],
            "The alert route is ALERT EVAL to ROUTE DISPATCH to NOTIFY.",
            min_edges=2,
        ),
        creation_task(
            "dgt-val-09", "validation", "expansion-observability:v1",
            "sequence_to_editable_xml",
            "Create an editable incident sequence showing what follows acknowledgement.",
            ["mso-seq-diagnose"],
            ["ACK", "DIAGNOSE"],
            "DIAGNOSE follows ACK.",
            min_edges=1,
        ),
        creation_task(
            "dgt-val-10", "validation", "expansion-observability:v1",
            "failure_state_diagram",
            "Create a degraded-collection state that cannot be mistaken for no alerts.",
            ["mso-degrade"],
            ["Collection interrupted", "DEGRADED", "Do not show No alerts"],
            "An interrupted collection must be represented as degraded and not disguised as no alerts.",
            min_edges=1,
        ),
        creation_task(
            "dgt-val-11", "validation", "expansion-material-governance:v1",
            "policy_diagram",
            "Create a material-source decision diagram for explicit, chartbook, and broader automatic scopes.",
            ["mgv-explicit", "mgv-chartbook", "mgv-broader"],
            ["Explicit material", "Selected scope only", "Chartbook materials first",
             "Automatic selection", "Broader authorized search"],
            "Explicit selection is limited to that scope; otherwise chartbook materials are searched first and broader search requires automatic selection.",
            min_edges=3,
        ),
        creation_task(
            "dgt-val-12", "validation", "expansion-material-governance:v1",
            "access_boundary_diagram",
            "Create an access-boundary diagram for chartbook, cross-user, and removed materials.",
            ["mgv-chartbook-narrow", "mgv-cross-user", "mgv-removed"],
            ["Mounted chartbook materials only", "No cross-user retrieval", "Removed material blocked"],
            "Retrieval is limited to mounted chartbook materials, never crosses users, and excludes removed material from new requests.",
        ),
        creation_task(
            "dgt-val-13", "validation", "expansion-material-governance:v1",
            "version_policy_diagram",
            "Create an editable version-conflict decision diagram.",
            ["mgv-version-pin", "mgv-missing", "mgv-conflict"],
            ["Pinned version", "Version unavailable", "Report unavailable",
             "Do not apply new value to old-version shape"],
            "Pinned evidence stays on that version; unavailable versions must be reported and new-version values cannot be applied to old-version shapes.",
            min_edges=2,
        ),
        creation_task(
            "dgt-val-14", "validation", "realistic-solar-manual:v1",
            "material_grounded_creation",
            "Create the editable solar commissioning route.",
            ["solar-route-precheck", "solar-route-run"],
            ["PRECHECK", "INSULATION TEST", "GRID SYNC", "RUN"],
            "The commissioning route starts PRECHECK to INSULATION TEST and finishes GRID SYNC to RUN.",
            min_edges=2,
        ),
        creation_task(
            "dgt-val-15", "validation", "realistic-solar-manual:v1",
            "procedure_diagram",
            "Create an editable operating-parameter diagram for wait time, torque, and current.",
            ["solar-wait", "solar-torque", "solar-current"],
            ["Wait 7 minutes", "6.2 N m", "69 A maximum continuous AC current"],
            "The procedure waits 7 minutes, uses 6.2 N m torque, and limits continuous AC current to 69 A.",
        ),
        creation_task(
            "dgt-val-16", "validation", "realistic-solar-manual:v1",
            "safety_gate_diagram",
            "Create a firmware and temperature safety-gate diagram.",
            ["solar-firmware", "solar-temperature"],
            ["Firmware 4.7.2 minimum", "82°C", "Three consecutive samples"],
            "Firmware must be at least 4.7.2 and the temperature gate requires at least 82°C for three consecutive samples.",
            min_edges=1,
        ),
        creation_task(
            "dgt-val-17", "validation", "drawio-collaboration-governance:v1",
            "role_policy_diagram",
            "Create a role matrix for Editor approval and Reviewer share permissions.",
            ["dcg-editor-approve", "dcg-reviewer-share"],
            ["Editor", "Cannot approve release", "Reviewer", "May review evidence", "Cannot manage shares"],
            "An Editor cannot approve a release; a Reviewer may review evidence but cannot manage shares.",
        ),
        creation_task(
            "dgt-val-18", "validation", "drawio-collaboration-governance:v1",
            "policy_diagram",
            "Create a chartbook retrieval decision diagram.",
            ["dcg-explicit", "dcg-removed", "dcg-chartbook-search", "dcg-broader-auto"],
            ["Explicit material limits request", "Removed material blocked", "Active chartbook first",
             "Broader search only with automatic selection"],
            "Explicit material limits the request, removed material is blocked, chartbook material is searched first, and broader search requires automatic selection.",
            source_scope_mode="selected_only",
            min_edges=3,
        ),
        creation_task(
            "dgt-val-19", "validation", "drawio-collaboration-governance:v1",
            "governance_timeline",
            "Create an editable governance timeline for retention and temporary reviewers.",
            ["dcg-retention", "dcg-temp-reviewer"],
            ["Retain 36 months", "Temporary Reviewer", "Maximum 8 hours"],
            "Governance records are retained for 36 months and Temporary Reviewer access lasts up to 8 hours.",
            min_edges=1,
        ),
        creation_task(
            "dgt-val-20", "validation", "expansion-observability:v1",
            "threshold_diagram",
            "Create an editable observability threshold and degraded-state diagram.",
            ["mso-slo", "mso-degrade"],
            ["Three consecutive samples", "Human confirmation", "Collection interrupted", "DEGRADED"],
            "Human confirmation requires three consecutive threshold breaches, while interrupted collection is represented as degraded.",
            min_edges=2,
        ),
    ])

    fixture["tasks"] = development + validation
    fixture["developmentMultimodalArtifactTaskIds"] = [
        "dgt-dev-02", "dgt-dev-05", "dgt-dev-10", "dgt-dev-12",
        "dgt-dev-14", "dgt-dev-15", "dgt-dev-18",
    ]
    fixture["developmentNoRetrievalTaskIds"] = ["dgt-dev-06"]
    fixture["validationMultimodalArtifactTaskIds"] = [
        "dgt-val-02", "dgt-val-04", "dgt-val-05", "dgt-val-08",
        "dgt-val-09", "dgt-val-14",
    ]
    return fixture


if __name__ == "__main__":
    V3.write_text(json.dumps(build(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {V3}")
