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
