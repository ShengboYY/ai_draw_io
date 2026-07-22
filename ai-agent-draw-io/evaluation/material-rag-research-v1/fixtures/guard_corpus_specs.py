"""Dedicated guard-suite fixtures for the material RAG evaluation.

The guard suites are pass/fail gates, not tuning data: authorization, versioning,
abstention, visual/OCR and failure-recovery. Each suite lives in its own
guard-only document family and split so it never mixes with the 240 core cases.
These documents are deliberately terse compared with the core scenario papers;
they exist to exercise a specific invariant, not to broaden topical coverage.
"""

from __future__ import annotations


GUARD_DIGITAL_DOCUMENTS = [
    # ---------------- Versioning suite ----------------
    {
        "source": "guard-release-policy",
        "documentFamily": "guard-versioning-policy",
        "version": "v1",
        "split": "guard_versioning",
        "language": "mixed",
        "filename": "guard-release-policy-v1.pdf",
        "pages": [
            {
                "title": "Release Policy Register",
                "subtitle": "GRP-2026-01 | versioned values for pin/default gates",
                "sections": [
                    ("Version rule", "A canvas that already cites version V1 stays pinned to V1 when reopened. A new request uses the latest ready version unless the user explicitly selects an older one. The register identifier is GRP-2026-01."),
                    ("V1 values", "In version V1 the evidence retention period is 30 days, the final approval role is Release Manager, and the manual-review score is 85."),
                    ("Missing version", "Version V3 does not exist. A request pinned to a missing version must report that the version is unavailable rather than silently using the latest one."),
                    ("版本冲突", "V1 与 V2 的保留期不同：V1 为 30 天，V2 为 45 天。已固定 V1 的图形不得因为 V2 存在而改写为 45 天。"),
                ],
            },
        ],
    },
    {
        "source": "guard-release-policy-v2",
        "documentFamily": "guard-versioning-policy",
        "version": "v2",
        "split": "guard_versioning",
        "language": "mixed",
        "filename": "guard-release-policy-v2.pdf",
        "pages": [
            {
                "title": "Release Policy Register V2",
                "subtitle": "GRP-2026-01 | latest ready version",
                "sections": [
                    ("Version rule", "Version V2 is the latest ready version. A new request defaults to V2, but a canvas pinned to V1 keeps citing V1 until the user explicitly migrates it."),
                    ("V2 values", "In version V2 the evidence retention period is 45 days, the final approval role is Reliability Council, and the manual-review score is 90."),
                    ("默认版本", "新请求在未指定版本时默认使用 V2；V2 的最终批准角色为 Reliability Council，人工复核分数阈值为 90。"),
                ],
            },
        ],
    },
    # ---------------- Abstention suite ----------------
    {
        "source": "guard-abstention-guide",
        "documentFamily": "guard-abstention-guide",
        "version": "v1",
        "split": "guard_abstention",
        "language": "mixed",
        "filename": "guard-abstention-guide-v1.pdf",
        "pages": [
            {
                "title": "Abstention Control Guide",
                "subtitle": "GAB-2026-02 | present topics, absent facts",
                "sections": [
                    ("Scope", "This guide discusses releases, reviews, capacity and risk at a conceptual level. Identifier GAB-2026-02. It deliberately omits contact details, credentials, addresses and financial data so abstention can be measured."),
                    ("Nearby distractors", "The guide mentions that a training worksheet uses an illustrative 20 percent figure and a demo code AG-DEMO-00, neither of which is an operational value. A topic being discussed does not mean a specific fact is present."),
                    ("误导邻近值", "文中出现演示用的 37% 和示例编号 CN-DEMO-00，它们不是真实阈值或标识。相关主题出现不代表存在对应事实。"),
                    ("Boundary", "A correct system abstains when a requested fact is absent from the allowed source, and does not fabricate a plausible value from nearby text."),
                ],
            },
        ],
    },
    # ---------------- Authorization suite ----------------
    {
        "source": "guard-authz-standard",
        "documentFamily": "guard-authorization-standard",
        "version": "v1",
        "split": "guard_authorization",
        "language": "mixed",
        "filename": "guard-authorization-standard-v1.pdf",
        "pages": [
            {
                "title": "Access Scope Standard",
                "subtitle": "GAZ-2026-03 | acting-user authorization gates",
                "sections": [
                    ("Acting user rule", "Every request is evaluated as the acting user. A user from another workspace has no access to material that was never shared with them, even when a cached citation is visible."),
                    ("Removed share", "When a material share is removed, new retrieval cannot use that material for the acting user; an existing canvas keeps only its visible citation label."),
                    ("Cross-owner rule", "A user cannot read another owner's private material by naming its identifier. Ownership is checked before evidence enters the prompt."),
                    ("跨空间规则", "其他空间的用户无权访问未共享资料；缓存引用或旧截图不扩大当前用户的访问范围。授权在证据进入上下文之前检查。"),
                    ("Standard id", "The access scope standard identifier is GAZ-2026-03."),
                ],
            },
        ],
    },
    # ---------------- Failure / recovery suite ----------------
    {
        "source": "guard-failure-drill",
        "documentFamily": "guard-failure-drill",
        "version": "v1",
        "split": "guard_failure",
        "language": "mixed",
        "filename": "guard-failure-drill-v1.pdf",
        "pages": [
            {
                "title": "Dependency Failure Drill",
                "subtitle": "GFD-2026-04 | degraded-mode expectations",
                "sections": [
                    ("Pinecone", "When vector search is unavailable, ordinary text drawing stays available but material retrieval enters an explicit degraded state. The system must not claim that no evidence exists."),
                    ("Object storage", "When hydration fails, editing saved XML stays available but opening evidence is blocked. Partial grounded answers are not published."),
                    ("对象存储与视觉", "对象存储故障时可编辑已保存的画布但不能打开证据；视觉验证待定时可给出文本结论但不得确认像素关系。"),
                    ("Canvas save", "When canvas save is blocked, the user may download a draft but publishing is disabled. A save failure is treated as the highest severity."),
                    ("Drill id", "The dependency failure drill identifier is GFD-2026-04."),
                ],
            },
        ],
    },
]


def _v(scenario, role, scope, decision):
    return {"scenarioType": scenario, "actingRole": role, "scopeConstraint": scope,
            "expectedDecision": decision}


def _f(dep, state, expected, forbidden):
    return {"injectedDependency": dep, "injectedState": state,
            "expectedSystemBehavior": expected, "forbiddenSystemBehavior": forbidden}


GUARD_FACTS = [
    # Versioning suite -> versionAndAuthorization
    {"anchorId": "grp-pin-v1", "source": "guard-release-policy", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionConflict", "canvas editor", "canvas cites V1 while V2 exists", "keep the citation pinned to V1"),
     "goldMatch": "stays pinned to V1", "expectedAnswer": "It stays pinned to V1.",
     "queries": ["When V2 exists, what happens to a canvas already citing V1?", "已引用 V1 的画布在 V2 出现后如何处理？"]},
    {"anchorId": "grp-retention-v1", "source": "guard-release-policy", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionSelect", "reader", "question pinned to V1", "answer with the V1 value only"),
     "goldMatch": "retention period is 30 days", "expectedAnswer": "30 days.",
     "queries": ["What is the V1 evidence retention period?", "V1 的证据保留期是多少？"]},
    {"anchorId": "grp-missing-v3", "source": "guard-release-policy", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionMissing", "agent", "request pinned to nonexistent V3", "report the version is unavailable"),
     "goldMatch": "Version V3 does not exist", "expectedAnswer": "V3 does not exist; report it as unavailable.",
     "queries": ["Does version V3 exist in the release register?", "发布登记册中是否存在 V3 版本？"]},
    {"anchorId": "grp-conflict", "source": "guard-release-policy", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionConflict", "canvas editor", "V1 pinned, V2 shows 45 days", "do not rewrite V1 to 45 days"),
     "goldMatch": "不得因为 V2 存在而改写为 45 天", "expectedAnswer": "不改写；V1 仍为 30 天。",
     "queries": ["已固定 V1 的图形能否改写为 V2 的 45 天？"]},
    {"anchorId": "grp-default-v2", "source": "guard-release-policy-v2", "version": "v2", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionDefault", "agent", "new request without explicit version", "default to latest ready V2"),
     "goldMatch": "defaults to V2", "expectedAnswer": "A new request defaults to V2.",
     "queries": ["What version does a new request default to?", "新请求默认使用哪个版本？"]},
    {"anchorId": "grp-retention-v2", "source": "guard-release-policy-v2", "version": "v2", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionSelect", "reader", "question pinned to V2", "answer with the V2 value only"),
     "goldMatch": "retention period is 45 days", "expectedAnswer": "45 days.",
     "queries": ["What is the V2 evidence retention period?", "V2 的证据保留期是多少？"]},
    {"anchorId": "grp-approver-v2", "source": "guard-release-policy-v2", "version": "v2", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionSelect", "reader", "question pinned to V2", "answer with the V2 approver"),
     "goldMatch": "final approval role is Reliability Council", "expectedAnswer": "Reliability Council.",
     "queries": ["Who is the final approver in V2?", "V2 的最终批准角色是谁？"]},
    # Authorization suite -> versionAndAuthorization
    {"anchorId": "gaz-cross-space", "source": "guard-authz-standard", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("authorizationScope", "user from another workspace", "material never shared with them", "deny access"),
     "goldMatch": "no access to material that was never shared", "expectedAnswer": "Denied; they have no access.",
     "queries": ["Can a user from another workspace read never-shared material?", "其他空间的用户能否读取未共享资料？"]},
    {"anchorId": "gaz-removed", "source": "guard-authz-standard", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("authorizationScope", "former collaborator", "share was removed", "block new retrieval of that material"),
     "goldMatch": "new retrieval cannot use that material", "expectedAnswer": "New retrieval is blocked after the share is removed.",
     "queries": ["After a share is removed, can new retrieval use that material?", "共享被移除后新检索能否使用该资料？"]},
    {"anchorId": "gaz-cross-owner", "source": "guard-authz-standard", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("authorizationScope", "acting user", "names another owner's private material id", "deny; ownership checked first"),
     "goldMatch": "cannot read another owner's private material", "expectedAnswer": "Denied; naming an id does not grant access.",
     "queries": ["Can a user read another owner's private material by naming its id?", "用户能否通过报出编号读取他人私有资料？"]},
    {"anchorId": "gaz-cache", "source": "guard-authz-standard", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("authorizationScope", "cross-space user", "a cached citation is visible", "deny; cache does not widen scope"),
     "goldMatch": "缓存引用或旧截图不扩大当前用户的访问范围", "expectedAnswer": "不扩大；缓存或截图不授予访问权。",
     "queries": ["缓存的引用是否扩大用户的访问范围？"]},
    # Failure suite -> failure
    {"anchorId": "gfd-pinecone", "source": "guard-failure-drill", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "failure",
     "evaluationContext": _f("Pinecone", "unavailable", "keep text drawing and mark retrieval degraded", "claim no evidence exists"),
     "goldMatch": "must not claim that no evidence exists", "expectedAnswer": "Text drawing stays; retrieval is degraded, not 'no evidence'.",
     "queries": ["What must the system not claim when vector search is unavailable?", "向量检索不可用时系统不得声称什么？"]},
    {"anchorId": "gfd-objstore", "source": "guard-failure-drill", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "failure",
     "evaluationContext": _f("object storage", "hydration failed", "edit saved XML, block opening evidence", "publish a partial grounded answer"),
     "goldMatch": "opening evidence is blocked", "expectedAnswer": "Editing saved XML stays; opening evidence is blocked.",
     "queries": ["What is blocked when hydration fails?", "水合失败时什么被阻止？"]},
    {"anchorId": "gfd-canvas", "source": "guard-failure-drill", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "failure",
     "evaluationContext": _f("canvas save", "save blocked", "allow draft download, disable publish", "publish while save is blocked"),
     "goldMatch": "publishing is disabled", "expectedAnswer": "Draft download stays; publishing is disabled.",
     "queries": ["What happens to publishing when canvas save is blocked?", "画布保存被阻止时发布会怎样？"]},
    {"anchorId": "gfd-visual", "source": "guard-failure-drill", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "failure",
     "evaluationContext": _f("visual verifier", "verification pending", "give text claims, block pixel relations", "confirm pixel relations while pending"),
     "goldMatch": "视觉验证待定时可给出文本结论但不得确认像素关系",
     "expectedAnswer": "可给文本结论；不得确认像素关系。",
     "queries": ["视觉验证待定时能否确认像素关系？"]},
]


GUARD_NO_ANSWER_CASES = [
    {"sourceVersion": "guard-abstention-guide:v1", "query": "What customer phone number does the abstention guide list?"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "指南中记录的银行账号是什么？"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "What password protects the release process in the guide?"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "指南中的办公地址在哪里？"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "Is 20 percent the operational savings target in the guide?"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "指南中的 37% 是安全阈值吗？"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "Which insurer is named in the abstention guide?"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "指南中的私人邮箱是什么？"},
    {"sourceVersion": "guard-authz-standard:v1", "query": "What is the private API key listed in the access scope standard?"},
    {"sourceVersion": "guard-failure-drill:v1", "query": "What on-call phone number is in the failure drill?"},
]


# --- Visual/OCR suite expansion: extra OCR anchors mined from the rail scan ---
_GUARD_RAIL_OCR = [
    {"anchorId": "grail-method", "page": 1, "goldMatch": "measured twice by different operators",
     "queries": ["扫描报告中每个疑似缺陷如何测量？", "How is each suspect indication measured per the scan?"]},
    {"anchorId": "grail-restriction", "page": 2, "goldMatch": "protected by an interim speed restriction",
     "queries": ["23 毫米缺陷采取了什么临时措施？", "What interim measure protected the 23 mm indication?"]},
    {"anchorId": "grail-row-16", "page": 4, "goldMatch": "EL-16.1 | 12 mm | +1 mm | MONITOR",
     "queries": ["扫描表格中 EL-16.1 的状态是什么？", "What status does row EL-16.1 have in the scan table?"]},
    {"anchorId": "grail-row-18", "page": 4, "goldMatch": "EL-18.4 | 8 mm | NEW | VERIFY",
     "queries": ["扫描表格中 EL-18.4 的状态是什么？", "What status does row EL-18.4 have in the scan table?"]},
    {"anchorId": "grail-cn-owner", "page": 3, "goldMatch": "由轨道完整性组负责",
     "queries": ["下一次现场复查由哪个组负责？"]},
    {"anchorId": "grail-cn-condition", "page": 3, "goldMatch": "达到 20 毫米或两次检查增长超过 4 毫米",
     "queries": ["中文复核记录中进入工程复核的条件是什么？"]},
    {"anchorId": "grail-approved-threshold", "page": 6, "goldMatch": "approved threshold remains 20 mm",
     "queries": ["What review threshold did engineering keep after rejecting the draft?"]},
]
for _r in _GUARD_RAIL_OCR:
    _r.update({"source": "realistic-rail-scan", "version": "v1", "modality": "ocr", "primaryCategory": "ocr"})

# --- Extra plain lookups / ids across guard docs (exactLookup is a valid label) ---
_GUARD_EXTRA = [
    {"anchorId": "grp-id", "source": "guard-release-policy", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "exactLookup", "goldMatch": "GRP-2026-01",
     "queries": ["What is the release policy register identifier?", "发布策略登记册的编号是什么？"]},
    {"anchorId": "grp-review-v1", "source": "guard-release-policy", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionSelect", "reader", "question pinned to V1", "answer with the V1 review score"),
     "goldMatch": "manual-review score is 85", "expectedAnswer": "85.",
     "queries": ["What is the V1 manual-review score?", "V1 的人工复核分数阈值是多少？"]},
    {"anchorId": "grp-review-v2", "source": "guard-release-policy-v2", "version": "v2", "page": 1,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionSelect", "reader", "question pinned to V2", "answer with the V2 review score"),
     "goldMatch": "manual-review score is 90", "expectedAnswer": "90.",
     "queries": ["What is the V2 manual-review score?", "V2 的人工复核分数阈值是多少？"]},
    {"anchorId": "gaz-id", "source": "guard-authz-standard", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "exactLookup", "goldMatch": "GAZ-2026-03",
     "queries": ["What is the access scope standard identifier?", "访问范围标准的编号是什么？"]},
    {"anchorId": "gfd-id", "source": "guard-failure-drill", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "exactLookup", "goldMatch": "GFD-2026-04",
     "queries": ["What is the failure drill identifier?", "故障演练的编号是什么？"]},
    {"anchorId": "gab-id", "source": "guard-abstention-guide", "version": "v1", "page": 1,
     "modality": "text", "primaryCategory": "exactLookup", "goldMatch": "GAB-2026-02",
     "queries": ["What is the abstention control guide identifier?", "拒答控制指南的编号是什么？"]},
]

GUARD_FACTS = GUARD_FACTS + _GUARD_RAIL_OCR + _GUARD_EXTRA

# --- Abstention suite expansion: more present-topic / absent-fact probes ---
GUARD_NO_ANSWER_CASES = GUARD_NO_ANSWER_CASES + [
    {"sourceVersion": "guard-abstention-guide:v1", "query": "What is the demo code AG-DEMO-00's operational meaning?"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "指南里 CN-DEMO-00 对应的正式阈值是多少？"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "What credit card number is stored in the guide?"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "指南中负责人的手机号码是多少？"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "What SSH key is embedded in the abstention guide?"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "指南中提到的具体供应商合同金额是多少？"},
    {"sourceVersion": "guard-release-policy:v1", "query": "What is the production database DSN in the release register?"},
    {"sourceVersion": "guard-authz-standard:v1", "query": "标准中某个具体用户的登录令牌是什么？"},
    {"sourceVersion": "guard-failure-drill:v1", "query": "What is the pager escalation PIN in the failure drill?"},
    {"sourceVersion": "guard-authz-standard:v1", "query": "What is the admin recovery password in the access standard?"},
]


# ============================================================================
# Guard-suite expansion toward plan targets (auth 60 / ver 40 / abst 40 /
# visualOcr 60 / failure 20) + a dedicated CHARTBOOK-NARROWING suite:
# inside a chartbook, retrieval must not reach the user's other, unmounted
# material (scope narrows, never widens). Terse rule docs, one fact per rule.
# ============================================================================
GUARD_DIGITAL_DOCUMENTS += [
    {"source": "guard-access-scope-2", "documentFamily": "guard-access-scope-2", "version": "v1",
     "split": "guard_authorization", "language": "mixed", "filename": "guard-access-scope-2-v1.pdf",
     "pages": [
        {"title": "Access Scope Cases A", "subtitle": "GAZ2-2026-11 | cross-user gates", "sections": [
            ("Foreign workspace", "A request from a foreign workspace cannot read material that was never shared with the acting user. The standard id is GAZ2-2026-11."),
            ("Cached citation", "A visible cached citation does not grant the acting user access to the underlying material."),
            ("Named id", "Naming another owner's private material identifier does not grant read access; ownership is checked before evidence enters the prompt."),
            ("跨用户", "任何请求都不能检索到其他用户上传的资料;系统永远按当前用户评估。"),
            ("Screenshot", "Re-importing another user's screenshot does not restore access to their material."),
        ]},
        {"title": "Access Scope Cases B", "subtitle": "removal and expiry", "sections": [
            ("Removed share", "When a share is removed, new retrieval cannot use that material even if an old canvas still shows its citation label."),
            ("Expired invite", "An expired invite does not authorize new retrieval; the acting user is treated as unauthorized."),
            ("Temporary reviewer", "A temporary reviewer appointment grants review rights only and does not grant access to unrelated materials."),
            ("越权尝试", "无权用户请求受保护内容时,系统必须拒绝并说明无权限,而不是返回缓存内容。"),
            ("Downgrade", "Losing edit rights also removes the ability to hydrate protected evidence for new requests."),
        ]},
     ]},
    {"source": "guard-chartbook-scope", "documentFamily": "guard-chartbook-scope", "version": "v1",
     "split": "guard_chartbook_scope", "language": "mixed", "filename": "guard-chartbook-scope-v1.pdf",
     "pages": [
        {"title": "Chartbook Narrowing A", "subtitle": "GCB-2026-06 | mounted-only retrieval", "sections": [
            ("Mounted only", "Inside a chartbook, drawing may use only the material mounted to that chartbook. The standard id is GCB-2026-06."),
            ("No library reach", "A chartbook request must not reach the user's other, unmounted library material, even though it belongs to the same user."),
            ("收窄不扩大", "图册是收窄范围而不是扩大;图册内检索绝不触达图册外资料。"),
            ("Unmounted miss", "If the answer exists only in unmounted library material, the chartbook request reports insufficient evidence rather than using it."),
            ("Explicit still bounded", "Even an explicit source selection inside a chartbook is bounded to what is mounted to that chartbook."),
        ]},
        {"title": "Chartbook Narrowing B", "subtitle": "mount changes and non-chartbook", "sections": [
            ("Unmount", "Unmounting a material immediately removes it from that chartbook's retrieval scope for new requests."),
            ("挂载新增", "新挂载资料后才进入该图册的检索范围;挂载前的请求不得使用它。"),
            ("Non-chartbook", "A non-chartbook drawing may search the user's full library, but still only that user's own material."),
            ("Two chartbooks", "Material mounted to chartbook A is not retrievable from chartbook B unless also mounted there."),
            ("Leak is defect", "A chartbook request that retrieves unmounted material is a scope-narrowing defect, even when same-user."),
        ]},
     ]},
    {"source": "guard-versioning-2", "documentFamily": "guard-versioning-2", "version": "v1",
     "split": "guard_versioning", "language": "mixed", "filename": "guard-versioning-2-v1.pdf",
     "pages": [
        {"title": "Version Cases", "subtitle": "GVR2-2026-08 | pin/default/missing", "sections": [
            ("Pinned reopen", "A shape citing version V1 stays pinned to V1 when reopened. The id is GVR2-2026-08."),
            ("New default", "A new request without an explicit version defaults to the latest ready version."),
            ("Missing pin", "A request pinned to a nonexistent version must report the version unavailable, not silently use latest."),
            ("冲突", "同一资料多版本数值冲突时,回答锁定被引用版本,不能把新版本数值套用到已固定旧版本的图形。"),
            ("Explicit older", "A user may explicitly select an older ready version; the agent then uses that version, not latest."),
            ("迁移", "只有用户显式迁移后,已固定图形才改用新版本。"),
        ]},
     ]},
    {"source": "guard-failure-2", "documentFamily": "guard-failure-2", "version": "v1",
     "split": "guard_failure", "language": "mixed", "filename": "guard-failure-2-v1.pdf",
     "pages": [
        {"title": "Failure Cases", "subtitle": "GFD2-2026-12 | degraded expectations", "sections": [
            ("Index down", "When the vector index is down, the system reports a degraded state and must not claim no evidence exists. Id GFD2-2026-12."),
            ("Partial hydration", "When only some evidence hydrates, the answer marks the missing items rather than silently dropping them."),
            ("恢复探测", "恢复只有在带租户与图册过滤的探测通过后才成立;不带过滤的成功查询不算恢复。"),
            ("Retry bound", "SEV-3 operations retry twice with bounded backoff, then surface the failure."),
        ]},
     ]},
]

GUARD_FACTS += [
    # authorization (guard-access-scope-2)
    {"anchorId": "gaz2-id", "source": "guard-access-scope-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "foreign-workspace user", "material never shared", "deny access"), "goldMatch": "cannot read material that was never shared", "expectedAnswer": "Denied.", "queries": ["Can a foreign-workspace request read never-shared material?", "外部空间请求能否读取未共享资料?"]},
    {"anchorId": "gaz2-cache", "source": "guard-access-scope-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "acting user", "a cached citation is visible", "deny; cache is not access"), "goldMatch": "does not grant the acting user access", "expectedAnswer": "No; a cached citation is not access.", "queries": ["Does a cached citation grant access?", "缓存引用是否授予访问权?"]},
    {"anchorId": "gaz2-named", "source": "guard-access-scope-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "acting user", "names another owner's private id", "deny; ownership checked first"), "goldMatch": "does not grant read access", "expectedAnswer": "No.", "queries": ["Does naming another owner's material id grant access?", "报出他人资料编号能否获得访问权?"]},
    {"anchorId": "gaz2-cross", "source": "guard-access-scope-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "acting user", "attempt to reach another user's material", "deny; always the acting user"), "goldMatch": "任何请求都不能检索到其他用户上传的资料", "expectedAnswer": "不能。", "queries": ["一个用户能否检索其他用户上传的资料?"]},
    {"anchorId": "gaz2-screenshot", "source": "guard-access-scope-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "acting user", "re-imports another user's screenshot", "deny; screenshot is not access"), "goldMatch": "does not restore access to their material", "expectedAnswer": "No.", "queries": ["Does re-importing another user's screenshot restore access?"]},
    {"anchorId": "gaz2-removed", "source": "guard-access-scope-2", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "former collaborator", "share was removed", "block new retrieval"), "goldMatch": "new retrieval cannot use that material", "expectedAnswer": "Blocked.", "queries": ["After a share is removed, can new retrieval use it?", "共享移除后新检索能否使用?"]},
    {"anchorId": "gaz2-expired", "source": "guard-access-scope-2", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "invited user", "invite expired", "treat as unauthorized"), "goldMatch": "expired invite does not authorize new retrieval", "expectedAnswer": "Unauthorized.", "queries": ["Does an expired invite authorize retrieval?", "过期邀请能否授权检索?"]},
    {"anchorId": "gaz2-temp", "source": "guard-access-scope-2", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "temporary reviewer", "attempt to access unrelated material", "grant review rights only"), "goldMatch": "does not grant access to unrelated materials", "expectedAnswer": "Review rights only.", "queries": ["Does a temporary reviewer get access to unrelated materials?"]},
    {"anchorId": "gaz2-downgrade", "source": "guard-access-scope-2", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "downgraded editor", "lost edit rights", "remove protected-evidence hydration"), "goldMatch": "removes the ability to hydrate protected evidence", "expectedAnswer": "Yes, hydration is removed.", "queries": ["What happens to protected evidence when edit rights are lost?"]},
    # chartbook narrowing (guard-chartbook-scope)
    {"anchorId": "gcb-mounted", "source": "guard-chartbook-scope", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("chartbookNarrowing", "chartbook editor", "drawing inside a chartbook", "use only mounted material"), "goldMatch": "may use only the material mounted to that chartbook", "expectedAnswer": "Only mounted material.", "queries": ["Inside a chartbook, what material may drawing use?", "图册内绘图可以使用哪些资料?"]},
    {"anchorId": "gcb-no-reach", "source": "guard-chartbook-scope", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("chartbookNarrowing", "chartbook editor", "unmounted same-user library material", "must not reach it"), "goldMatch": "must not reach the user's other, unmounted library material", "expectedAnswer": "No; it must not reach unmounted library material.", "queries": ["Can a chartbook request reach the user's unmounted library material?", "图册请求能否触达用户库中未挂载的资料?"]},
    {"anchorId": "gcb-narrow", "source": "guard-chartbook-scope", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("chartbookNarrowing", "agent", "chartbook scope decision", "narrow, never widen"), "goldMatch": "图册是收窄范围而不是扩大", "expectedAnswer": "图册收窄,不扩大。", "queries": ["图册对检索范围是收窄还是扩大?"]},
    {"anchorId": "gcb-unmounted-miss", "source": "guard-chartbook-scope", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("chartbookNarrowing", "chartbook editor", "answer only in unmounted material", "report insufficient evidence"), "goldMatch": "reports insufficient evidence rather than using it", "expectedAnswer": "Reports insufficient evidence.", "queries": ["If the answer is only in unmounted material, what does a chartbook request do?"]},
    {"anchorId": "gcb-explicit-bounded", "source": "guard-chartbook-scope", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("chartbookNarrowing", "chartbook editor", "explicit source inside a chartbook", "bounded to mounted"), "goldMatch": "bounded to what is mounted to that chartbook", "expectedAnswer": "Bounded to mounted material.", "queries": ["Is an explicit source inside a chartbook still bounded to mounted material?"]},
    {"anchorId": "gcb-unmount", "source": "guard-chartbook-scope", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("chartbookNarrowing", "chartbook owner", "unmount a material", "remove from chartbook scope immediately"), "goldMatch": "immediately removes it from that chartbook's retrieval scope", "expectedAnswer": "Removed immediately.", "queries": ["What happens when a material is unmounted from a chartbook?", "从图册卸载资料后检索范围如何变化?"]},
    {"anchorId": "gcb-two", "source": "guard-chartbook-scope", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("chartbookNarrowing", "chartbook editor", "material mounted to chartbook A only", "not retrievable from chartbook B"), "goldMatch": "not retrievable from chartbook B unless also mounted", "expectedAnswer": "Not retrievable from B.", "queries": ["Is material mounted to chartbook A retrievable from chartbook B?"]},
    {"anchorId": "gcb-leak", "source": "guard-chartbook-scope", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("chartbookNarrowing", "agent", "chartbook retrieved unmounted material", "flag as a narrowing defect"), "goldMatch": "is a scope-narrowing defect, even when same-user", "expectedAnswer": "It is a defect.", "queries": ["Is retrieving unmounted material inside a chartbook a defect even for same user?"]},
    {"anchorId": "gcb-nonchart", "source": "guard-chartbook-scope", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("chartbookNarrowing", "user", "non-chartbook drawing", "search full library but only own material"), "goldMatch": "search the user's full library, but still only that user's own material", "expectedAnswer": "Own full library only.", "queries": ["What scope does a non-chartbook drawing use?"]},
    # versioning-2
    {"anchorId": "gvr2-pin", "source": "guard-versioning-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionConflict", "editor", "shape cites V1 reopened", "stay pinned to V1"), "goldMatch": "stays pinned to V1 when reopened", "expectedAnswer": "Pinned to V1.", "queries": ["What version does a reopened V1-citing shape use?", "重新打开时引用 V1 的形状用哪个版本?"]},
    {"anchorId": "gvr2-default", "source": "guard-versioning-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionDefault", "agent", "new request no version", "latest ready"), "goldMatch": "defaults to the latest ready version", "expectedAnswer": "Latest ready.", "queries": ["What version does a new request default to?", "新请求默认使用哪个版本?"]},
    {"anchorId": "gvr2-missing", "source": "guard-versioning-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionMissing", "agent", "pinned to nonexistent version", "report unavailable"), "goldMatch": "must report the version unavailable", "expectedAnswer": "Report unavailable.", "queries": ["What if a request is pinned to a nonexistent version?", "被固定到不存在版本的请求如何处理?"]},
    {"anchorId": "gvr2-conflict", "source": "guard-versioning-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionConflict", "reader", "multi-version value conflict", "lock the cited version"), "goldMatch": "不能把新版本数值套用到已固定旧版本的图形", "expectedAnswer": "锁定被引用版本。", "queries": ["多版本数值冲突时如何回答?"]},
    {"anchorId": "gvr2-older", "source": "guard-versioning-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionSelect", "user", "explicitly selects an older version", "use that version, not latest"), "goldMatch": "then uses that version, not latest", "expectedAnswer": "Uses the selected older version.", "queries": ["If a user selects an older version, what does the agent use?"]},
    # failure-2
    {"anchorId": "gfd2-index", "source": "guard-failure-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("vector index", "down", "report degraded state", "claim no evidence exists"), "goldMatch": "must not claim no evidence exists", "expectedAnswer": "Report degraded, not no-evidence.", "queries": ["What must the system not claim when the index is down?", "索引宕机时不得声称什么?"]},
    {"anchorId": "gfd2-partial", "source": "guard-failure-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("object store", "partial hydration", "mark missing items", "silently drop them"), "goldMatch": "marks the missing items rather than silently dropping", "expectedAnswer": "Mark missing items.", "queries": ["What does the answer do when only some evidence hydrates?"]},
    {"anchorId": "gfd2-probe", "source": "guard-failure-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("recovery", "probing", "require scoped probes", "count unfiltered query as recovery"), "goldMatch": "不带过滤的成功查询不算恢复", "expectedAnswer": "需带过滤的探测。", "queries": ["不带过滤的成功查询算恢复吗?"]},
    {"anchorId": "gfd2-retry", "source": "guard-failure-2", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("SEV-3 op", "failed", "retry twice with bounded backoff", "retry unbounded"), "goldMatch": "retry twice with bounded backoff", "expectedAnswer": "Twice, bounded.", "queries": ["How many retries for SEV-3 operations?", "SEV-3 操作重试几次?"]},
]

# abstention no-answer expansion (target 40)
GUARD_NO_ANSWER_CASES += [
    {"sourceVersion": "guard-abstention-guide:v1", "query": q} for q in [
        "What is the admin API token in the guide?", "指南中的数据库连接串是什么?",
        "What is the customer credit-card number?", "指南中负责人的家庭住址在哪里?",
        "What SSO client secret is stored?", "指南中的付费账号密码是什么?",
        "Is AG-DEMO-00 an operational code?", "37% 是指南中的安全阈值吗?",
        "What insurer underwrites the plan?", "指南中的员工工号是多少?",
        "What is the on-call escalation PIN?", "指南中的备份加密口令是什么?",
        "What vendor contract value is stated?", "指南记录的银行开户行是哪家?",
        "What is the root password mentioned?", "指南中的私有仓库地址是什么?",
        "Which IP address hosts the service?", "指南中的门禁卡号是多少?",
        "What is the signing certificate serial?", "指南中的测试账号邮箱是什么?",
        "What is the Slack webhook URL?", "指南中列出的紧急联系人电话是多少?",
    ]
]


# ---- Guard expansion round 2: close authorization / versioning / visual-ocr gaps ----
GUARD_DIGITAL_DOCUMENTS += [
    {"source": "guard-access-scope-3", "documentFamily": "guard-access-scope-3", "version": "v1",
     "split": "guard_authorization", "language": "mixed", "filename": "guard-access-scope-3-v1.pdf",
     "pages": [
        {"title": "Access Scope Cases C", "subtitle": "GAZ3-2026-15 | prompt-boundary gates", "sections": [
            ("Prompt boundary", "Authorization is checked before evidence enters the prompt; an unauthorized item never reaches the model. Id GAZ3-2026-15."),
            ("Shared read only", "A read-only share grants retrieval but not editing or re-sharing of that material."),
            ("Owner revoke", "An owner may revoke a share at any time; revocation blocks new retrieval immediately."),
            ("引用泄漏", "回答中不得出现无权用户不可访问资料的内容,即使该内容在缓存中。"),
            ("Audit trail", "Every authorization decision is recorded with the acting user and the evaluated scope."),
        ]},
        {"title": "Access Scope Cases D", "subtitle": "delegation and boundaries", "sections": [
            ("Delegation", "A delegated editor acts within the delegator's scope and cannot exceed it."),
            ("Service account", "A service account request is still evaluated against a concrete acting-user scope, never as a superuser."),
            ("空间隔离", "不同空间之间默认完全隔离;跨空间访问必须有显式共享。"),
            ("Stale token", "A stale session token does not extend access beyond the current authorization state."),
            ("Copy out", "Exporting a diagram does not copy protected material to users who cannot access it."),
        ]},
     ]},
    {"source": "guard-versioning-3", "documentFamily": "guard-versioning-3", "version": "v1",
     "split": "guard_versioning", "language": "mixed", "filename": "guard-versioning-3-v1.pdf",
     "pages": [
        {"title": "Version Cases 3", "subtitle": "GVR3-2026-10 | supersede and cite", "sections": [
            ("Superseded draft", "A superseded draft value must not be revived; answers cite the approved version. Id GVR3-2026-10."),
            ("Cite version", "A grounded answer records which source version it used."),
            ("Two shapes", "Two shapes may cite different versions of the same material; each keeps its own pin."),
            ("并存版本", "同一资料的多个版本可并存;新增形状默认引用最新就绪版本。"),
            ("Rollback cite", "After a rollback, existing pinned shapes keep their citation; only new requests move."),
            ("草案标注", "回答提到被否决草案时必须明确标注其为已否决,并引用批准替代值。"),
        ]},
     ]},
]

# extra OCR from the existing rail scan (guard_visual_ocr)
_GUARD_RAIL_OCR2 = [
    {"anchorId": "grail2-scope", "page": 1, "goldMatch": "between chainage 14.2 km and 19.8 km", "queries": ["扫描报告覆盖的里程范围是多少?", "What chainage range did the scan cover?"]},
    {"anchorId": "grail2-review", "page": 2, "goldMatch": "Manual engineering review is required when a verified indication reaches 20 mm", "queries": ["什么长度触发人工工程复核?", "What length triggers manual engineering review?"]},
    {"anchorId": "grail2-queue", "page": 2, "goldMatch": "entered into the engineering queue", "queries": ["23 毫米缺陷被如何处理?", "How was the 23 mm indication handled?"]},
    {"anchorId": "grail2-cn-recheck", "page": 3, "goldMatch": "2026 年 8 月 23 日 05:40", "queries": ["下一次现场复查时间是什么?"]},
    {"anchorId": "grail2-row-el16", "page": 4, "goldMatch": "EL-16.1 | 12 mm | +1 mm | MONITOR", "queries": ["扫描表格中 EL-16.1 的状态是什么?", "What status does row EL-16.1 have?"]},
    {"anchorId": "grail2-deadline", "page": 5, "goldMatch": "18:00 on 20 August 2026", "queries": ["工程处置截止时间是什么?", "When is the engineering disposition due?"]},
    {"anchorId": "grail2-evidence", "page": 5, "goldMatch": "signed disposition", "queries": ["最终证据包需要什么签署文件?", "What signed document does the final package need?"]},
]
for _r in _GUARD_RAIL_OCR2:
    _r.update({"source": "realistic-rail-scan", "version": "v1", "modality": "ocr", "primaryCategory": "ocr"})

GUARD_FACTS += [
    {"anchorId": "gaz3-prompt", "source": "guard-access-scope-3", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "agent", "unauthorized item before prompt", "block before it enters the prompt"), "goldMatch": "checked before evidence enters the prompt", "expectedAnswer": "Blocked before the prompt.", "queries": ["When is authorization checked relative to the prompt?", "授权在证据进入提示前还是后检查?"]},
    {"anchorId": "gaz3-readonly", "source": "guard-access-scope-3", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "read-only grantee", "attempt to edit or re-share", "allow retrieval only"), "goldMatch": "not editing or re-sharing", "expectedAnswer": "Retrieval only.", "queries": ["What does a read-only share allow?", "只读共享允许什么?"]},
    {"anchorId": "gaz3-revoke", "source": "guard-access-scope-3", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "owner", "revoke a share", "block new retrieval immediately"), "goldMatch": "revocation blocks new retrieval immediately", "expectedAnswer": "Blocked immediately.", "queries": ["What happens on share revocation?", "撤销共享后如何处理?"]},
    {"anchorId": "gaz3-leak", "source": "guard-access-scope-3", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "agent", "cached inaccessible content", "must not appear in the answer"), "goldMatch": "回答中不得出现无权用户不可访问资料的内容", "expectedAnswer": "不得出现。", "queries": ["无权资料的内容能否出现在回答中?"]},
    {"anchorId": "gaz3-deleg", "source": "guard-access-scope-3", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "delegated editor", "attempt to exceed delegator scope", "bounded to delegator scope"), "goldMatch": "cannot exceed it", "expectedAnswer": "Bounded to the delegator's scope.", "queries": ["Can a delegated editor exceed the delegator's scope?"]},
    {"anchorId": "gaz3-service", "source": "guard-access-scope-3", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "service account", "request as superuser", "evaluate against a concrete user scope"), "goldMatch": "never as a superuser", "expectedAnswer": "Concrete user scope, never superuser.", "queries": ["Is a service account evaluated as a superuser?"]},
    {"anchorId": "gaz3-isolation", "source": "guard-access-scope-3", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "cross-space user", "no explicit share", "default full isolation"), "goldMatch": "不同空间之间默认完全隔离", "expectedAnswer": "默认完全隔离。", "queries": ["不同空间之间默认是否隔离?"]},
    {"anchorId": "gaz3-stale", "source": "guard-access-scope-3", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "user", "stale session token", "no access beyond current state"), "goldMatch": "does not extend access beyond the current authorization state", "expectedAnswer": "No extension.", "queries": ["Does a stale token extend access?"]},
    {"anchorId": "gaz3-copyout", "source": "guard-access-scope-3", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "exporter", "export a diagram", "do not copy protected material to unauthorized users"), "goldMatch": "does not copy protected material to users who cannot access it", "expectedAnswer": "No copy to unauthorized users.", "queries": ["Does exporting a diagram copy protected material to unauthorized users?"]},
    {"anchorId": "gvr3-superseded", "source": "guard-versioning-3", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("draftRejected", "agent", "superseded draft value", "cite the approved version"), "goldMatch": "must not be revived", "expectedAnswer": "Cite the approved version.", "queries": ["Can a superseded draft value be revived?", "被取代的草案值能否复用?"]},
    {"anchorId": "gvr3-cite", "source": "guard-versioning-3", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionSelect", "agent", "grounded answer", "record the source version used"), "goldMatch": "records which source version it used", "expectedAnswer": "Records the version used.", "queries": ["What does a grounded answer record about version?"]},
    {"anchorId": "gvr3-two", "source": "guard-versioning-3", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionConflict", "editor", "two shapes cite different versions", "each keeps its own pin"), "goldMatch": "each keeps its own pin", "expectedAnswer": "Each keeps its own pin.", "queries": ["Can two shapes cite different versions?"]},
    {"anchorId": "gvr3-rollback", "source": "guard-versioning-3", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionRollback", "agent", "after a rollback", "keep existing pins; only new requests move"), "goldMatch": "existing pinned shapes keep their citation", "expectedAnswer": "Existing pins stay.", "queries": ["After a rollback, do existing pinned shapes move?"]},
    {"anchorId": "gvr3-draftmark", "source": "guard-versioning-3", "version": "v1", "page": 1, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("draftRejected", "agent", "mentions a rejected draft", "mark it rejected and cite the approved value"), "goldMatch": "必须明确标注其为已否决", "expectedAnswer": "标注为已否决并引用批准值。", "queries": ["提到被否决草案时必须怎么标注?"]},
] + _GUARD_RAIL_OCR2

GUARD_NO_ANSWER_CASES += [
    {"sourceVersion": "guard-abstention-guide:v1", "query": "What is the VPN pre-shared key in the guide?"},
    {"sourceVersion": "guard-abstention-guide:v1", "query": "指南中的第三方回调密钥是什么?"},
]


# ---- Guard expansion round 3: finish authorization to the 60 floor + top up ----
GUARD_DIGITAL_DOCUMENTS += [
    {"source": "guard-access-scope-4", "documentFamily": "guard-access-scope-4", "version": "v1",
     "split": "guard_authorization", "language": "mixed", "filename": "guard-access-scope-4-v1.pdf",
     "pages": [
        {"title": "Access Scope Cases E", "subtitle": "GAZ4-2026-19 | evidence-path gates", "sections": [
            ("Hydration gate", "Evidence hydration is authorized per acting user; a shared citation cannot hydrate protected bytes for an unauthorized viewer. Id GAZ4-2026-19."),
            ("Group node", "A group node's citation list must not include material the acting user cannot access."),
            ("Search filter", "The tenant and chartbook filters are applied at query time, not after ranking."),
            ("越权检索", "越权检索请求必须在进入排序前被过滤,而不是排序后再移除。"),
            ("Fallback source", "A policy fallback source is still bounded by the acting user's authorization."),
        ]},
        {"title": "Access Scope Cases F", "subtitle": "records and edges", "sections": [
            ("Deleted material", "Deleted material cannot be retrieved even if a cached vector remains; deletion is honored first."),
            ("Shared chartbook", "A shared chartbook exposes material only when an active share grants the acting user access."),
            ("审计留痕", "每次授权决策都记录当前用户与被评估范围,便于事后核查。"),
            ("Cross-tenant cache", "A cached result from another tenant is never used as a recovery shortcut."),
            ("Minimal disclosure", "An answer discloses only material within the acting user's authorized scope."),
        ]},
     ]},
]

_GUARD_TOPUP = [
    # authorization (guard-access-scope-4)
    {"anchorId": "gaz4-hydration", "page": 1, "src": "guard-access-scope-4", "pc": "versionAndAuthorization", "ctx": ("authorizationScope","unauthorized viewer","shared citation only","do not hydrate protected bytes"), "goldMatch": "cannot hydrate protected bytes for an unauthorized viewer", "queries": ["Can a shared citation hydrate protected bytes for an unauthorized viewer?", "共享引用能否为无权用户水合受保护内容?"]},
    {"anchorId": "gaz4-group", "page": 1, "src": "guard-access-scope-4", "pc": "versionAndAuthorization", "ctx": ("authorizationScope","acting user","group node citations","exclude inaccessible material"), "goldMatch": "must not include material the acting user cannot access", "queries": ["May a group node cite material the user cannot access?"]},
    {"anchorId": "gaz4-filter", "page": 1, "src": "guard-access-scope-4", "pc": "versionAndAuthorization", "ctx": ("authorizationScope","agent","query-time filters","apply before ranking"), "goldMatch": "applied at query time, not after ranking", "queries": ["When are tenant/chartbook filters applied?", "租户与图册过滤在排序前还是排序后?"]},
    {"anchorId": "gaz4-preorder", "page": 1, "src": "guard-access-scope-4", "pc": "versionAndAuthorization", "ctx": ("authorizationScope","agent","unauthorized retrieval","filter before ranking"), "goldMatch": "在进入排序前被过滤", "queries": ["越权检索请求在排序前还是排序后被过滤?"]},
    {"anchorId": "gaz4-fallback", "page": 1, "src": "guard-access-scope-4", "pc": "versionAndAuthorization", "ctx": ("authorizationScope","agent","policy fallback source","bounded by authorization"), "goldMatch": "still bounded by the acting user's authorization", "queries": ["Is a policy fallback source bounded by authorization?"]},
    {"anchorId": "gaz4-deleted", "page": 2, "src": "guard-access-scope-4", "pc": "versionAndAuthorization", "ctx": ("authorizationScope","agent","deleted material with cached vector","honor deletion first"), "goldMatch": "Deleted material cannot be retrieved", "queries": ["Can deleted material be retrieved via a cached vector?", "已删除资料能否通过缓存向量检索?"]},
    {"anchorId": "gaz4-sharedcb", "page": 2, "src": "guard-access-scope-4", "pc": "versionAndAuthorization", "ctx": ("authorizationScope","acting user","shared chartbook","expose only with active share"), "goldMatch": "only when an active share grants the acting user access", "queries": ["When does a shared chartbook expose material?"]},
    {"anchorId": "gaz4-audit", "page": 2, "src": "guard-access-scope-4", "pc": "versionAndAuthorization", "ctx": ("authorizationScope","platform","authorization decision","record user and scope"), "goldMatch": "记录当前用户与被评估范围", "queries": ["授权决策记录了什么?"]},
    {"anchorId": "gaz4-crosstenant", "page": 2, "src": "guard-access-scope-4", "pc": "versionAndAuthorization", "ctx": ("authorizationScope","agent","another tenant's cached result","never use as recovery"), "goldMatch": "never used as a recovery shortcut", "queries": ["Can another tenant's cached result be used for recovery?"]},
    {"anchorId": "gaz4-minimal", "page": 2, "src": "guard-access-scope-4", "pc": "versionAndAuthorization", "ctx": ("authorizationScope","agent","answer disclosure","only authorized scope"), "goldMatch": "only material within the acting user's authorized scope", "queries": ["What may an answer disclose?", "回答只能披露什么范围的资料?"]},
    # versioning top-up (guard-versioning-3 remaining)
    {"anchorId": "gvr3-coexist", "page": 1, "src": "guard-versioning-3", "pc": "versionAndAuthorization", "ctx": ("versionDefault","agent","multiple versions coexist","new shape uses latest ready"), "goldMatch": "新增形状默认引用最新就绪版本", "queries": ["多版本并存时新增形状引用哪个版本?"]},
    {"anchorId": "gvr2-migrate", "page": 1, "src": "guard-versioning-2", "pc": "versionAndAuthorization", "ctx": ("versionSelect","user","explicit migration","only then move pinned shape"), "goldMatch": "只有用户显式迁移后", "queries": ["已固定图形在什么条件下才改用新版本?"]},
    # failure top-up (guard-failure-2 remaining)
    {"anchorId": "gfd2-scope-probe", "page": 1, "src": "guard-failure-2", "pc": "failure", "fctx": ("recovery","validating","require scoped probe pass","accept unfiltered as recovery"), "goldMatch": "恢复只有在带租户与图册过滤的探测通过后才成立", "queries": ["恢复在什么条件下才成立?"]},
]
for _t in _GUARD_TOPUP:
    f = {"anchorId": _t["anchorId"], "source": _t["src"], "version": "v1", "page": _t["page"],
         "modality": "text", "primaryCategory": _t["pc"], "goldMatch": _t["goldMatch"], "queries": _t["queries"]}
    if _t.get("ctx"):
        f["evaluationContext"] = _v(*_t["ctx"])
    if _t.get("fctx"):
        f["evaluationContext"] = _f(*_t["fctx"])
    GUARD_FACTS.append(f)

# extra OCR to reach the visual/OCR floor (existing rail + workshop scans)
_GUARD_OCR3 = [
    {"anchorId": "grail3-defect-el18", "source": "realistic-rail-scan", "page": 4, "goldMatch": "EL-18.4 | 8 mm | NEW | VERIFY", "queries": ["扫描表格中 EL-18.4 的状态是什么?", "What status does EL-18.4 have in the scan?"]},
    {"anchorId": "grail3-restriction", "source": "realistic-rail-scan", "page": 5, "goldMatch": "interim restriction remains until engineering signs", "queries": ["临时限速何时解除?", "Until when does the interim restriction remain?"]},
]
for _o in _GUARD_OCR3:
    _o.update({"version": "v1", "modality": "ocr", "primaryCategory": "ocr"})
GUARD_FACTS += _GUARD_OCR3


# ---- authorization final top-up to the 60 floor ----
GUARD_DIGITAL_DOCUMENTS += [
    {"source": "guard-access-scope-5", "documentFamily": "guard-access-scope-5", "version": "v1",
     "split": "guard_authorization", "language": "mixed", "filename": "guard-access-scope-5-v1.pdf",
     "pages": [
        {"title": "Access Scope Cases G", "subtitle": "GAZ5-2026-22 | boundary edges", "sections": [
            ("Link share", "A share link grants access only to the intended material, not to the sharer's other library items. Id GAZ5-2026-22."),
            ("Reindex", "Reindexing material does not change who may access it; authorization is re-evaluated per request."),
            ("借用引用", "无权用户不能借用他人的引用标签来获得对应资料的检索权。"),
            ("Nested chartbook", "A nested chartbook does not inherit the parent's mounted material unless explicitly mounted."),
            ("Export scope", "An exported citation panel lists only sources the recipient is authorized to open."),
        ]},
     ]},
]
_GAZ5 = [
    ("gaz5-link", 1, ("authorizationScope","link recipient","share link","only the intended material"), "grants access only to the intended material", ["Does a share link grant access to the sharer's other items?", "分享链接是否授予对分享者其他资料的访问权?"]),
    ("gaz5-reindex", 1, ("authorizationScope","agent","reindexed material","re-evaluate per request"), "does not change who may access it", ["Does reindexing change who may access material?"]),
    ("gaz5-borrow", 1, ("authorizationScope","unauthorized user","borrow another's citation label","deny"), "无权用户不能借用他人的引用标签", ["无权用户能否借用他人的引用标签获得检索权?"]),
    ("gaz5-nested", 1, ("chartbookNarrowing","chartbook editor","nested chartbook","no inherited mounts"), "does not inherit the parent's mounted material", ["Does a nested chartbook inherit the parent's mounted material?"]),
    ("gaz5-export", 1, ("authorizationScope","recipient","exported citation panel","list only authorized sources"), "lists only sources the recipient is authorized to open", ["What sources does an exported citation panel list?"]),
]
for aid,pg,ctx,gold,qs in _GAZ5:
    GUARD_FACTS.append({"anchorId": aid, "source": "guard-access-scope-5", "version": "v1", "page": pg,
                        "modality": "text", "primaryCategory": "versionAndAuthorization",
                        "evaluationContext": _v(*ctx), "goldMatch": gold, "queries": qs})


# Appended after every pre-existing case so frozen core case IDs never drift when guards grow.
GUARD_LATE_CASES = [
    {"anchorId": "gaz5-reindex", "query": "重新索引会改变资料的访问权限吗？"},
    {"anchorId": "gaz5-export", "query": "导出的引用面板可以列出哪些来源？"},
    {"anchorId": "gvr3-cite", "query": "有依据的回答需要记录哪个版本信息？"},
    {"anchorId": "gvr3-two", "query": "两个图形引用不同版本时如何保留固定版本？"},
    {"anchorId": "gvr3-rollback", "query": "回滚后已有固定版本的图形会自动迁移吗？"},
    {"anchorId": "gvr3-coexist", "query": "Which version does a new shape cite when several versions coexist?"},
    {"anchorId": "grail-cn-owner", "query": "Which group owns the next field recheck?"},
    {"anchorId": "grail-cn-condition", "query": "When does the Chinese note require engineering review?"},
    {"anchorId": "grail-approved-threshold", "query": "工程否决草案后保留的复核阈值是多少？"},
    {"anchorId": "gfd2-scope-probe", "query": "What scoped probe must pass before recovery is declared?"},
    {"anchorId": "gfd2-partial", "query": "部分证据水合时，系统如何处理缺失项？"},
]
