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
