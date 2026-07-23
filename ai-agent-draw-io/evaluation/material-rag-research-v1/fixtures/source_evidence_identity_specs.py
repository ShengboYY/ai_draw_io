"""Publisher-owned evidence identities for the active draw.io source materials.

These records are source metadata. They intentionally contain neither task prompts nor expected answers.
"""

SOURCE_EVIDENCE_IDENTITIES = [
    {"sourceEvidenceId": "daa-route-scope", "source": "drawio-agent-architecture", "version": "v1",
     "page": 3, "match": {"kind": "visual_page"}},
    {"sourceEvidenceId": "daa-route-compose", "source": "drawio-agent-architecture", "version": "v1",
     "page": 3, "match": {"kind": "visual_page"}},
    {"sourceEvidenceId": "daa-version-pin", "source": "drawio-agent-architecture", "version": "v1",
     "page": 5, "match": {"kind": "exact_text", "text": "remains pinned to V1"}},
    {"sourceEvidenceId": "dwh-sequence-type", "source": "drawio-workflow-handbook", "version": "v1",
     "page": 3, "match": {"kind": "exact_text", "text": "描述系统交互时首选时序图"}},
    {"sourceEvidenceId": "dwh-handoff-type", "source": "drawio-workflow-handbook", "version": "v1",
     "page": 3, "match": {"kind": "exact_text", "text": "责任交接则首选泳道图"}},
    {"sourceEvidenceId": "dwh-factual-edit", "source": "drawio-workflow-handbook", "version": "v1",
     "page": 5, "match": {"kind": "exact_text", "text": "重新检查证据"}},
    {"sourceEvidenceId": "dwh-canonical", "source": "drawio-workflow-handbook", "version": "v1",
     "page": 5, "match": {"kind": "exact_text", "text": "draw.io XML 是可继续编辑的正式结果"}},
    {"sourceEvidenceId": "dpw-budgets", "source": "drawio-planning-workshop-scan", "version": "v1",
     "page": 4, "match": {"kind": "exact_text", "text": "Candidate retrieval: 40 | Hydration: 16 | Final bundle: 8"}},
    {"sourceEvidenceId": "dcg-reviewer-share", "source": "drawio-collaboration-governance", "version": "v1",
     "page": 2, "match": {"kind": "exact_text", "text": "Reviewer may approve evidence changes but cannot manage shares"}},
    {"sourceEvidenceId": "dcg-route-draft", "source": "drawio-collaboration-governance", "version": "v1",
     "page": 4, "match": {"kind": "visual_page"}},
    {"sourceEvidenceId": "dcg-route-publish", "source": "drawio-collaboration-governance", "version": "v1",
     "page": 4, "match": {"kind": "visual_page"}},
    {"sourceEvidenceId": "dcg-temp-reviewer", "source": "drawio-collaboration-governance", "version": "v1",
     "page": 6, "match": {"kind": "exact_text", "text": "up to 8 hours"}},
]


# These identities are publisher-owned locator metadata for the expanded v3 task families.
# They are deliberately kept separate from task prompts and evaluator expected answers.
_V3_EXACT_TEXT_IDENTITIES = [
    ("daa-bundle", "drawio-agent-architecture", 4, "final evidence bundle contains at most 8 items"),
    ("daa-candidates", "drawio-agent-architecture", 4, "retrieves 40 evidence candidates"),
    ("daa-explicit-source", "drawio-agent-architecture", 4, "explicit selection wins"),
    ("daa-hydration", "drawio-agent-architecture", 4, "At most 16 authorized candidates are hydrated"),
    ("daa-latest", "drawio-agent-architecture", 5, "defaults to the latest ready version"),
    ("dcc-threshold", "expansion-datacenter-change", 6, "连续三个采样周期超过 85% 才进入人工复核"),
    ("dcg-broader-auto", "drawio-collaboration-governance", 3, "considered only when automatic selection is enabled"),
    ("dcg-chartbook-search", "drawio-collaboration-governance", 3, "search begins with material attached to the active chartbook"),
    ("dcg-editor-approve", "drawio-collaboration-governance", 2, "cannot approve a release"),
    ("dcg-explicit", "drawio-collaboration-governance", 3, "limits the request"),
    ("dcg-removed", "drawio-collaboration-governance", 3, "blocks new retrieval"),
    ("dcg-retention", "drawio-collaboration-governance", 5, "retained for 36 months"),
    ("dpw-degraded", "drawio-planning-workshop-scan", 3, "DEGRADED SEARCH, not NO EVIDENCE"),
    ("dpw-editable-scan", "drawio-planning-workshop-scan", 5, "Stable cell identifiers and valid draw.io XML are required"),
    ("dpw-removed", "drawio-planning-workshop-scan", 3, "cannot be used for a new request"),
    ("dpw-source-order", "drawio-planning-workshop-scan", 3, "explicit source first"),
    ("dpw-visual-scan", "drawio-planning-workshop-scan", 5, "Arrow direction, containment and raster table cells require pixel verification"),
    ("dwh-auto-scope", "drawio-workflow-handbook", 2, "才进入更广的授权资料搜索"),
    ("dwh-explicit", "drawio-workflow-handbook", 2, "只能在该范围内检索"),
    ("fas-depth", "expansion-field-audit-scan", 2, "verified wear depth measured 27 mm"),
    ("fas-row-status", "expansion-field-audit-scan", 4, "B-16 | 27 mm | +6 mm | ENGINEERING REVIEW"),
    ("fas-threshold", "expansion-field-audit-scan", 2, "reaches 25 mm or growth exceeds 5 mm"),
    ("mgv-broader", "expansion-material-governance", 2, "considered only when automatic selection is enabled"),
    ("mgv-chartbook", "expansion-material-governance", 2, "先搜索当前图册允许共享的资料"),
    ("mgv-chartbook-narrow", "expansion-material-governance", 3, "只能使用该图册挂载的资料"),
    ("mgv-conflict", "expansion-material-governance", 4, "不能把新版本数值套用到已固定旧版本的图形"),
    ("mgv-cross-user", "expansion-material-governance", 3, "任何请求都不能检索到其他用户上传的资料"),
    ("mgv-explicit", "expansion-material-governance", 2, "只能在该范围内检索"),
    ("mgv-missing", "expansion-material-governance", 4, "必须报告版本不可用"),
    ("mgv-removed", "expansion-material-governance", 3, "new retrieval cannot use that material"),
    ("mgv-version-pin", "expansion-material-governance", 4, "仍固定引用该版本"),
    ("mso-degrade", "expansion-observability", 6, "不得把采集中断伪装成「无告警」"),
    ("mso-slo", "expansion-observability", 6, "连续三个采样周期超过阈值才触发人工确认"),
    ("ota-approve-rollout", "scenario-ota-rollout", 6, "质量复核者可以给出放量与回滚建议，但不能单独批准"),
    ("ota-version-pin", "scenario-ota-rollout", 6, "仍固定引用原版本"),
    ("pre-canvas", "expansion-platform-resilience", 2, "canvas-save failure is SEV-1"),
    ("pre-objstore", "expansion-platform-resilience", 2, "Object store"),
    ("pre-sev2", "expansion-platform-resilience", 5, "SEV-2 means a material task cannot complete without data loss"),
    ("pre-sev3", "expansion-platform-resilience", 5, "SEV-3 means a retryable delay with preserved work"),
    ("pre-severity", "expansion-platform-resilience", 5, "SEV-1 means possible cross-scope disclosure"),
    ("pre-vector", "expansion-platform-resilience", 2, "manual drawing remains available but material search is blocked"),
    ("solar-current", "realistic-solar-manual", 3, "maximum continuous AC current is 69 A"),
    ("solar-firmware", "realistic-solar-manual", 5, "最低固件版本为 4.7.2"),
    ("solar-temperature", "realistic-solar-manual", 5, "连续三个采样周期达到或超过 82 摄氏度"),
    ("solar-torque", "realistic-solar-manual", 3, "6.2 N m"),
    ("solar-wait", "realistic-solar-manual", 2, "wait 7 minutes"),
]

_V3_VISUAL_PAGE_IDENTITIES = [
    ("dcc-arch-apply", "expansion-datacenter-change", 3),
    ("dcc-arch-route", "expansion-datacenter-change", 3),
    ("dwh-route-bundle", "drawio-workflow-handbook", 4),
    ("dwh-route-draft", "drawio-workflow-handbook", 4),
    ("mso-arch-notify", "expansion-observability", 3),
    ("mso-arch-route", "expansion-observability", 3),
    ("mso-seq-diagnose", "expansion-observability", 4),
    ("ota-arch-route", "scenario-ota-rollout", 3),
    ("ota-canary-batch", "scenario-ota-rollout", 4),
    ("ota-canary-complete", "scenario-ota-rollout", 4),
    ("ota-canary-sequence", "scenario-ota-rollout", 4),
    ("ota-route-repo", "scenario-ota-rollout", 3),
    ("ota-route-vehicle", "scenario-ota-rollout", 3),
    ("pss-arch-route", "scenario-payment-settlement", 3),
    ("pss-route-acquire", "scenario-payment-settlement", 3),
    ("pss-route-settle", "scenario-payment-settlement", 3),
    ("pss-settle-clearing", "scenario-payment-settlement", 4),
    ("pss-settle-notify", "scenario-payment-settlement", 4),
    ("pss-settle-sequence", "scenario-payment-settlement", 4),
    ("solar-route-precheck", "realistic-solar-manual", 4),
    ("solar-route-run", "realistic-solar-manual", 4),
]

SOURCE_EVIDENCE_IDENTITIES.extend(
    {
        "sourceEvidenceId": source_evidence_id,
        "source": source,
        "version": "v1",
        "page": page,
        "match": {"kind": "exact_text", "text": text},
    }
    for source_evidence_id, source, page, text in _V3_EXACT_TEXT_IDENTITIES
)
SOURCE_EVIDENCE_IDENTITIES.extend(
    {
        "sourceEvidenceId": source_evidence_id,
        "source": source,
        "version": "v1",
        "page": page,
        "match": {"kind": "visual_page"},
    }
    for source_evidence_id, source, page in _V3_VISUAL_PAGE_IDENTITIES
)
