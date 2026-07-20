"""Application-specific long-document fixtures for the draw.io agent evaluation corpus."""

from __future__ import annotations


DRAWIO_DIGITAL_DOCUMENTS = [
    {
        "source": "drawio-agent-architecture",
        "documentFamily": "drawio-agent-architecture-blueprint",
        "version": "v1",
        "split": "development",
        "language": "en",
        "filename": "drawio-agent-architecture-blueprint-v1.pdf",
        "template": "paper",
        "footerLabel": "Draw.io Agent Architecture",
        "bodyFontSize": 10.4,
        "bodyLeading": 14.8,
        "pages": [
            {
                "title": "Draw.io Agent Architecture Blueprint",
                "subtitle": "DAA-2026-17 | evidence-grounded diagram generation",
                "layout": "columns",
                "sections": [
                    ("Purpose", "This blueprint defines how the draw.io agent turns user material into an editable diagram while preserving evidence links. The controlled architecture identifier is DAA-2026-17. DAA-2026-71 is a retired prototype. The document covers ingestion, retrieval, diagram planning, XML assembly and citation display rather than general chat behavior."),
                    ("Product boundary", "A user may select explicit material, search the current chartbook or allow automatic source selection. The agent may use model knowledge only when product policy permits and must distinguish that content from material-backed claims. It never treats a visually plausible node label as evidence by itself."),
                    ("Design invariant", "Every material-backed shape keeps an evidence reference that can resolve to source version, page and region. A diagram remains editable after generation; exporting an image does not replace the canonical draw.io XML. Authorization and version rules are checked before evidence enters the prompt."),
                    ("Out of scope", "This synthetic blueprint contains no production tenant identifier, private user email, database password or live Pinecone host. Questions requesting them require abstention."),
                ],
            },
            {
                "title": "1. Component ownership",
                "subtitle": "Native table for service-to-responsibility mapping",
                "sections": [
                    ("Ownership rule", "Ownership follows the component that makes the decision, not the component that transports the result. The Retrieval Orchestrator selects candidates; the Canvas Composer turns an approved plan into draw.io XML."),
                ],
                "table": {
                    "headers": ["Component", "Responsibility", "Input", "Output", "Owner"],
                    "rows": [
                        ["Material Gateway", "scope + version", "user request", "allowed sources", "Platform"],
                        ["Evidence Index", "search", "query", "ranked evidence", "Retrieval"],
                        ["Plan Builder", "diagram intent", "evidence", "typed plan", "Agent"],
                        ["Canvas Composer", "draw.io XML", "typed plan", "editable canvas", "Diagram"],
                        ["Citation Binder", "source links", "shape refs", "citation panel", "Trust"],
                    ],
                    "colWidths": [91, 95, 75, 92, 77],
                },
                "afterSections": [
                    ("Reading note", "Canvas Composer is owned by the Diagram team. Citation Binder can reject an unresolved reference but cannot change the selected source version."),
                ],
            },
            {
                "title": "2. Request-to-canvas route",
                "subtitle": "Raster-only architecture relationship",
                "sections": [
                    ("Figure scope", "The route between planning and citation is encoded only in Figure 2. Surrounding prose does not repeat the node sequence. Visual cases must inspect the diagram and preserve arrow direction."),
                    ("Failure boundary", "If citation binding fails, the request remains a draft and the user sees an evidence warning. The system must not silently publish a canvas with missing source references."),
                ],
                "diagram": {
                    "filename": "drawio-agent-request-route.png",
                    "title": "DRAW.IO AGENT REQUEST ROUTE",
                    "nodes": ["SCOPE SOURCES", "RETRIEVE EVIDENCE", "BUILD PLAN", "COMPOSE CANVAS"],
                    "edgeLabels": ["authorized", "ranked", "validated"],
                },
            },
            {
                "title": "3. Retrieval and context budgets",
                "subtitle": "Stage limits are not interchangeable",
                "sections": [
                    ("Candidate retrieval", "The baseline retrieves 40 evidence candidates before reauthorization. Authorization may remove a candidate but may not replace it with evidence from an unapproved source. Candidate count is a recall budget, not the number of shapes shown to the user."),
                    ("Hydration", "At most 16 authorized candidates are hydrated from object storage for the baseline context-selection stage. A timeout produces an explicit missing-artifact record. Hydration does not expand the user scope or switch to a newer version."),
                    ("Final bundle", "The final evidence bundle contains at most 8 items. Multi-part comparisons are complete only when every required evidence group survives. The nearby value 12 belongs to an experimental layout branch and is not the baseline bundle limit."),
                    ("Source selection", "When the user names a material, explicit selection wins. Without an explicit source, the current chartbook is searched before broader allowed material. A zero-result search may trigger the policy-defined fallback, but the response must label the fallback source."),
                ],
            },
            {
                "title": "4. Version and degraded behavior",
                "subtitle": "Pinned references, latest requests and unavailable dependencies",
                "layout": "columns",
                "sections": [
                    ("Version pinning", "A shape that already cites material version V1 remains pinned to V1 when reopened. A new request defaults to the latest ready version unless the user explicitly chooses an older one. The agent never rewrites an existing citation merely because V2 exists."),
                    ("Pinecone unavailable", "If the vector service is unavailable, ordinary text drawing remains available but material-backed retrieval enters an explicit degraded state. The agent must not claim that no evidence exists when search could not run."),
                    ("Visual verification", "A relationship found only in pixels requires verified visual evidence. Text extraction alone cannot confirm arrow direction, containment or the row-column association of a raster table."),
                    ("Recovery", "Recovered retrieval is tested with the same tenant and version filters before normal status returns. Cached results from another user or chartbook are never used as a recovery shortcut."),
                    ("Approval", "Blueprint version 1.0 was approved on 28 November 2026 by the Agent Architecture Council. An October draft used a 10-item final bundle and is superseded."),
                ],
            },
            {
                "title": "5. Evidence-to-shape contract",
                "subtitle": "Required fields and unsupported shortcuts",
                "sections": [
                    ("Shape contract", "Each grounded shape stores a stable shape identifier, source version, page or visual region and evidence grade. A group node can summarize several facts, but its citation list must still cover every material-backed claim."),
                    ("Editable output", "The canonical output is draw.io XML with stable cell identifiers. PNG and SVG exports are delivery formats. Re-importing a screenshot does not restore evidence bindings or semantic group structure."),
                    ("Review rule", "A reviewer may accept layout changes without changing evidence. A factual label change requires renewed evidence validation. Removing a cited shape may remove its citation, but citations used by remaining shapes must stay resolvable."),
                    ("Unsupported requests", "The blueprint does not reveal credentials, tenant secrets, private user contact details or production endpoints. Related architectural terms are not evidence for those values."),
                ],
            },
        ],
    },
    {
        "source": "drawio-workflow-handbook",
        "documentFamily": "drawio-diagram-workflow-handbook",
        "version": "v1",
        "split": "development",
        "language": "zh",
        "filename": "drawio-diagram-workflow-handbook-v1.pdf",
        "bodyFontSize": 10.6,
        "bodyLeading": 15.2,
        "pages": [
            {
                "title": "Draw.io Agent 制图工作流手册",
                "subtitle": "DWH-2026-12 | 从资料检索到可编辑画布",
                "sections": [
                    ("适用范围", "本手册用于规范 Agent 根据 PDF、图片和已有资料生成 draw.io 图的工作流。正式编号为 DWH-2026-12；培训样例 DWH-2026-21 不属于本版本。工作流覆盖需求澄清、资料范围、证据选择、图形规划、画布生成和引用复核。"),
                    ("基本原则", "先确定用户要表达的关系，再选择图型。系统不能因为资料中出现许多名词就默认生成架构图，也不能把一段顺序文字直接解释成并行流程。每个关键节点应能追溯到资料证据或明确标注为用户提供。"),
                    ("拒答边界", "手册没有记录用户密码、付费账号、私人电话号码、生产租户编号或内部访问令牌。相关问题必须拒答，不能从截图、文件名或常识中猜测。"),
                ],
            },
            {
                "title": "1. 需求与资料范围",
                "subtitle": "显式选择、当前图册与自动检索",
                "layout": "columns",
                "sections": [
                    ("显式资料", "用户点名某份资料或版本时，只能在该范围内检索。若指定版本没有目标事实，应说明证据不足，而不是自动切换到最新版。"),
                    ("当前图册", "用户没有点名资料但正在编辑图册时，系统先搜索当前图册允许共享的资料。被移除的资料不再参与新请求。"),
                    ("自动检索", "只有在没有显式资料且当前图册没有足够证据时，才进入更广的授权资料搜索。搜索范围变化需要记录在回答元数据中。"),
                    ("是否检索", "纯布局操作，例如把已有节点改成两列，不需要重新检索。新增事实节点、比较数值或解释图片箭头则必须检索并验证证据。"),
                    ("澄清条件", "当“它”“这个流程”等指代可能对应多个对象时，先请求澄清。不能用最高相似度结果替代用户选择。"),
                ],
            },
            {
                "title": "2. 图型选择与规划",
                "subtitle": "原生表格与近义图型干扰",
                "sections": [("选择说明", "图型由关系决定。时间顺序、责任交接、层级归属和数据关联不能仅根据主题词互换。")],
                "table": {
                    "headers": ["关系", "首选图型", "必要证据", "常见误用", "复核角色"],
                    "rows": [
                        ["有序步骤", "流程图", "先后与分支", "思维导图", "流程负责人"],
                        ["系统交互", "时序图", "参与者与消息", "组织结构图", "架构师"],
                        ["包含关系", "架构图", "边界与依赖", "时间线", "系统负责人"],
                        ["数据关系", "ER 图", "实体与基数", "泳道图", "数据负责人"],
                        ["责任交接", "泳道图", "角色与动作", "单列清单", "业务负责人"],
                    ],
                    "colWidths": [76, 76, 100, 88, 94],
                },
                "afterSections": [("读取提示", "描述系统交互时首选时序图；责任交接则首选泳道图。两者都可能包含箭头，但箭头的语义不同。")],
            },
            {
                "title": "3. 规划到画布",
                "subtitle": "Raster-only 流程关系",
                "sections": [
                    ("图示说明", "图 3 只在像素中给出从证据包到画布草稿的节点顺序。正文故意不复述相邻关系，用于检查视觉理解是否真正参与。"),
                    ("校验规则", "计划必须先通过范围、版本和证据完整性检查，才能交给画布生成器。布局检查通过不代表事实检查通过。"),
                ],
                "diagram": {
                    "filename": "drawio-workflow-plan-route.png",
                    "title": "DRAW.IO PLANNING ROUTE",
                    "nodes": ["EVIDENCE BUNDLE", "TYPED PLAN", "LAYOUT CHECK", "CANVAS DRAFT"],
                    "edgeLabels": ["ground", "validate", "compose"],
                },
            },
            {
                "title": "4. 编辑、引用与导出",
                "subtitle": "中文长段落与对象边界",
                "sections": [
                    ("编辑", "用户可以移动、分组或重新着色节点而不改变事实。若修改节点名称、数值、箭头方向或责任角色，系统必须重新检查证据。仅改变字体大小不需要重新检索。"),
                    ("引用", "引用面板按形状显示资料名称、版本、页码和证据区域。跨页比较必须保留两个来源位置。摘要节点可以合并展示，但不能把多个证据压缩成一个不可追溯的页码。"),
                    ("导出", "draw.io XML 是可继续编辑的正式结果；SVG 适合网页，PNG 适合预览，PDF 适合审阅。导出格式不会改变资料权限，也不能把已失效引用重新变成有效引用。"),
                    ("回退", "画布生成失败时保留已验证的 typed plan，并显示可重试状态。系统不能用无引用的旧截图假装生成成功。"),
                ],
            },
            {
                "title": "5. 审批与版本说明",
                "subtitle": "草案冲突、完成标准与无答案控制",
                "layout": "columns",
                "sections": [
                    ("批准版本", "手册 1.0 版于 2026 年 12 月 6 日由 Diagram Experience Lead 批准。11 月草案允许在布局操作时自动补充事实，评审认为风险过高并删除该规则。"),
                    ("完成标准", "正式完成需要可编辑 XML、通过的布局检查、全部关键形状的证据绑定以及无未解决的权限警告。只有 PNG 预览不算完成。"),
                    ("复核抽样", "每次发布至少抽查 12 个复杂图，其中中文、英文和图像证据案例均需覆盖。抽查通过不替代专项权限守门测试。"),
                    ("不支持信息", "手册没有生产数据库地址、用户私人邮箱、支付信息或服务密码。系统必须明确证据不足。"),
                    ("引用要求", "资料范围问题引用第 2 页，图型选择引用第 3 页，视觉顺序引用第 4 页，导出与完成标准分别引用第 5、6 页。"),
                ],
            },
        ],
    },
    {
        "source": "drawio-collaboration-governance",
        "documentFamily": "drawio-collaboration-governance",
        "version": "v1",
        "split": "validation",
        "language": "en",
        "filename": "drawio-collaboration-governance-v1.pdf",
        "template": "field_memo",
        "headerLabel": "COLLABORATION / GOVERNANCE",
        "footerLabel": "DCG fixture",
        "bodyFontSize": 10.4,
        "bodyLeading": 14.8,
        "pages": [
            {
                "title": "Collaborative Chartbook Governance",
                "subtitle": "DCG-2026-05 | sharing, review and evidence scope",
                "sections": [
                    ("Purpose", "This standard governs shared chartbooks used by the draw.io agent. Its identifier is DCG-2026-05. It distinguishes canvas edit rights, material visibility and approval rights; receiving one permission never implies the others."),
                    ("Core rule", "Every request is evaluated as the acting user. A shared chartbook may expose material only when an active share grants that user access. Cached evidence, an old citation or another editor's access does not expand the acting user's scope."),
                    ("Unsupported data", "The standard contains no real member email, invite token, tenant secret or production audit endpoint."),
                ],
            },
            {
                "title": "Roles and decisions",
                "subtitle": "Native responsibility table",
                "sections": [("Role separation", "Editors change the canvas, reviewers resolve evidence concerns and owners manage sharing. A single person may hold several roles, but every action is checked against the role required for that action.")],
                "table": {
                    "headers": ["Role", "Edit canvas", "Use shared material", "Approve", "Manage shares"],
                    "rows": [
                        ["Viewer", "no", "if granted", "no", "no"],
                        ["Editor", "yes", "if granted", "no", "no"],
                        ["Reviewer", "comment", "if granted", "yes", "no"],
                        ["Owner", "yes", "yes", "yes", "yes"],
                    ],
                    "colWidths": [80, 86, 128, 68, 91],
                },
                "afterSections": [("Table result", "A Reviewer may approve evidence changes but cannot manage shares. An Editor may edit the canvas but cannot approve a release.")],
            },
            {
                "title": "Source-scope decision",
                "subtitle": "Explicit, chartbook and automatic selection",
                "layout": "columns",
                "sections": [
                    ("Explicit source", "A user-selected material limits the request even when other shared sources look more relevant."),
                    ("Chartbook search", "Without an explicit source, search begins with material attached to the active chartbook and visible to the acting user."),
                    ("Broader search", "Broader allowed material is considered only when automatic selection is enabled. The response records that scope expansion."),
                    ("Removed access", "Removing a share blocks new retrieval immediately. Existing canvases retain visible citation metadata but cannot hydrate protected content for an unauthorized viewer."),
                    ("No-result state", "A no-result response is valid only after every allowed search path completed. An unavailable index produces degraded state, not no answer."),
                ],
            },
            {
                "title": "Review and release route",
                "subtitle": "Raster-only approval relationship",
                "sections": [
                    ("Figure scope", "Figure 4 defines the ordered review route. The arrow after EVIDENCE REVIEW is present only in the raster diagram."),
                    ("Blocking issue", "An unresolved unauthorized-source finding blocks release regardless of layout quality. Review comments may be closed only by a reviewer or owner."),
                ],
                "diagram": {
                    "filename": "drawio-governance-review-route.png",
                    "title": "CHARTBOOK RELEASE ROUTE",
                    "nodes": ["DRAFT", "EVIDENCE REVIEW", "OWNER APPROVAL", "PUBLISH"],
                    "edgeLabels": ["submit", "resolved", "authorized"],
                },
            },
            {
                "title": "Audit retention and revision",
                "subtitle": "Dates, rejected draft and evidence package",
                "sections": [
                    ("Retention", "Share changes, source selections and approval decisions are retained for 36 months. Canvas move events without factual changes are retained for 12 months."),
                    ("Revision", "Version 1.0 was approved on 18 December 2026 by the Collaboration Governance Board. A draft proposed 24-month share retention; that value was rejected."),
                    ("Evidence package", "Release evidence includes the acting-user scope, selected source versions, resolved findings and owner approval. A screenshot of the final canvas alone is incomplete."),
                    ("Abstention", "No invite token, private email, password or tenant key is included. Requests for those values are unsupported."),
                ],
            },
            {
                "title": "Exceptions and incident handling",
                "subtitle": "Temporary access never bypasses authorization",
                "sections": [
                    ("Emergency review", "An owner may appoint a temporary reviewer for up to 8 hours. The appointment grants review rights only and does not grant access to unrelated materials."),
                    ("Incident", "If unauthorized evidence appears, publishing is disabled, affected citations are quarantined and an audit record is opened. The canvas is not deleted automatically because unaffected user edits may remain valid."),
                    ("Restoration", "Release resumes only after scope is re-evaluated, unauthorized evidence is removed and a reviewer approves the corrected evidence package."),
                ],
            },
        ],
    },
    {
        "source": "drawio-recovery-runbook",
        "documentFamily": "drawio-agent-recovery-runbook",
        "version": "v1",
        "split": "holdout",
        "language": "en",
        "filename": "drawio-agent-recovery-runbook-v1.pdf",
        "template": "paper",
        "footerLabel": "Draw.io Agent Reliability",
        "bodyFontSize": 10.4,
        "bodyLeading": 14.8,
        "pages": [
            {
                "title": "Draw.io Agent Degraded-Mode Runbook",
                "subtitle": "DRR-2026-09 | retrieval, OCR and canvas recovery",
                "layout": "columns",
                "sections": [
                    ("Scope", "Runbook DRR-2026-09 defines user-visible behavior when a dependency fails during material-grounded diagram creation. It covers vector search, object storage, OCR, visual verification and canvas persistence. DRR-2026-90 is a load-test sheet."),
                    ("Invariant", "Failure never expands authorization, changes a pinned version or converts an unverified fact into a verified one. The user sees which capability is unavailable and which operations remain safe."),
                    ("Severity", "SEV-1 means possible cross-scope disclosure or corrupted saved canvas. SEV-2 means a material-backed task cannot complete without data loss. SEV-3 means a retryable delay with preserved work."),
                    ("No secrets", "This fixture contains no live host, API key, on-call phone number or customer identifier."),
                ],
            },
            {
                "title": "1. Failure matrix",
                "subtitle": "Dependency, state and allowed action",
                "sections": [("Matrix rule", "The allowed action is conservative. A capability may stay available only when it does not depend on the failed evidence path.")],
                "table": {
                    "headers": ["Failure", "User state", "Allowed", "Blocked", "Severity"],
                    "rows": [
                        ["Pinecone", "retrieval degraded", "manual drawing", "material search", "SEV-2"],
                        ["Object store", "hydration failed", "edit saved XML", "open evidence", "SEV-2"],
                        ["OCR", "scan unavailable", "native PDF", "scan claims", "SEV-3"],
                        ["Visual verifier", "visual pending", "text claims", "pixel relations", "SEV-3"],
                        ["Canvas save", "save blocked", "download draft", "publish", "SEV-1"],
                    ],
                    "colWidths": [90, 105, 97, 91, 61],
                },
                "afterSections": [("Matrix result", "During a Pinecone failure, manual drawing remains available but material search is blocked. A canvas-save failure is SEV-1 because publishing could lose or corrupt user work.")],
            },
            {
                "title": "2. Detection and containment route",
                "subtitle": "Raster-only incident flow",
                "sections": [
                    ("Figure scope", "Figure 2 carries the containment sequence. The relation following FREEZE MATERIAL TASKS is not repeated in native text."),
                    ("Containment", "In-flight material tasks keep their evidence manifest for diagnosis, but no partial grounded answer is published. Ordinary local shape edits may continue when canvas persistence is healthy."),
                ],
                "diagram": {
                    "filename": "drawio-recovery-containment-route.png",
                    "title": "DEGRADED-MODE CONTAINMENT",
                    "nodes": ["DETECT", "FREEZE MATERIAL TASKS", "VERIFY SCOPE", "RESTORE SERVICE"],
                    "edgeLabels": ["classify", "preserve", "probe"],
                },
            },
            {
                "title": "3. Recovery verification",
                "subtitle": "Probe order, deadlines and false positives",
                "sections": [
                    ("Probe order", "First verify tenant and chartbook filters, then version selection, then evidence hydration, and finally diagram generation. A successful unfiltered query is not a valid recovery probe."),
                    ("Search recovery", "Pinecone returns to normal only after 12 consecutive scoped probes succeed across three test tenants. The nearby value 21 belongs to a capacity drill and is not the recovery threshold."),
                    ("OCR recovery", "OCR recovery requires anchor recall of at least 0.95 on the pinned scan suite and no cross-page substitution. Character error rate alone cannot prove row or arrow correctness."),
                    ("Canvas recovery", "A saved draft is reopened, edited and saved again before publish is enabled. The test must preserve stable cell identifiers and citation bindings."),
                ],
            },
            {
                "title": "4. User messaging and retry policy",
                "subtitle": "Honest degraded states",
                "layout": "columns",
                "sections": [
                    ("Message", "The user message names the unavailable capability and states whether work was preserved. It never says 'no evidence exists' when retrieval did not execute."),
                    ("Automatic retry", "SEV-3 operations retry twice with bounded backoff. SEV-1 and SEV-2 require a verified dependency state before retrying material work."),
                    ("Fallback", "Manual drawing and edits to already loaded shapes may continue if they do not add material-backed facts. Model knowledge is not an invisible substitute for failed retrieval."),
                    ("Cancellation", "The user may cancel a queued retry without deleting the saved canvas or evidence audit record."),
                    ("Status clear", "The degraded banner clears only after the same scoped operation that failed has passed verification."),
                ],
            },
            {
                "title": "5. Approval and post-incident evidence",
                "subtitle": "Exit criteria and unsupported requests",
                "sections": [
                    ("Approval", "Runbook version 1.0 was approved on 21 December 2026 by the Reliability Council. A draft allowed five scoped probes; it was rejected in favor of twelve consecutive probes."),
                    ("Exit criteria", "Closure requires the incident timeline, affected capability, scope-verification results, recovery probes, user-message sample and confirmation that temporary vectors were deleted."),
                    ("Owner", "The Incident Commander approves service restoration. The Diagram team validates canvas integrity, while the Retrieval team validates scoped search. Neither team can approve its own authorization exception."),
                    ("Unsupported", "No live API key, endpoint, customer record or private on-call number appears. Those requests require abstention."),
                ],
            },
        ],
    },
]


DRAWIO_SCANNED_DOCUMENT = {
    "source": "drawio-planning-workshop-scan",
    "documentFamily": "drawio-planning-workshop-scan",
    "version": "v1",
    "split": "development",
    "language": "mixed",
    "filename": "drawio-planning-workshop-scan-v1.pdf",
    "imagePrefix": "drawio-workshop-scan",
    "pages": [
        {"title": "DRAW.IO PLANNING WORKSHOP", "subtitle": "DPW-2026-33 | scanned design notes", "sections": [
            ("Goal", "The workshop defined how a material-backed request becomes an editable diagram. The workshop identifier is DPW-2026-33; DPW-2026-03 is a room booking."),
            ("Scope", "Participants covered source selection, evidence grouping, layout choice and citation review. No production credentials or customer data were used."),
            ("Decision", "The canonical deliverable is editable draw.io XML, not a screenshot."),
        ]},
        {"title": "1. REQUEST TRIAGE", "subtitle": "scan of retrieval-decision notes", "sections": [
            ("Layout only", "Moving existing shapes or changing colors does not require retrieval when no factual label changes."),
            ("Material fact", "Adding a new fact, numeric comparison or source-backed relationship requires retrieval and evidence validation."),
            ("Clarify", "If a pronoun could refer to two diagrams, ask the user before searching."),
        ]},
        {"title": "2. SOURCE ORDER", "subtitle": "explicit source and chartbook scope", "sections": [
            ("Order", "Use an explicit source first, then current-chartbook material, then broader allowed material when automatic selection is enabled."),
            ("Removed material", "A removed share cannot be used for a new request even when an old canvas still displays its citation label."),
            ("No result", "Index failure is DEGRADED SEARCH, not NO EVIDENCE."),
        ]},
        {"title": "3. EVIDENCE BOARD", "subtitle": "scanned thresholds and owners", "sections": [
            ("Budgets", "Candidate retrieval: 40 | Hydration: 16 | Final bundle: 8"),
            ("Completeness", "Comparison questions keep ALL required evidence parts. One matching page is incomplete when the answer spans two pages."),
            ("Owner", "The Trust reviewer resolves unsupported citation findings."),
        ]},
        {"title": "4. CANVAS REVIEW", "subtitle": "visual and editable-output checks", "sections": [
            ("Visual", "Arrow direction, containment and raster table cells require pixel verification."),
            ("Editable", "Stable cell identifiers and valid draw.io XML are required before completion."),
            ("Export", "PNG is a preview. It does not preserve semantic groups or evidence bindings."),
        ]},
        {"title": "5. WORKSHOP ACTIONS", "subtitle": "dates, roles and negative evidence", "sections": [
            ("Deadline", "The typed-plan validation checklist is due on 14 January 2027. The 10 January date is a template-review meeting."),
            ("Approval", "The Agent UX Lead approves the checklist; the workshop facilitator records comments."),
            ("Unsupported", "No password, user phone number, tenant key or production endpoint appears in these notes."),
        ]},
    ],
}


DRAWIO_FACTS = [
    {"anchorId": "daa-id", "source": "drawio-agent-architecture", "version": "v1", "page": 1, "modality": "text", "goldMatch": "DAA-2026-17", "queries": ["What is the architecture blueprint identifier?", "架构蓝图的正式编号是什么？"]},
    {"anchorId": "daa-canvas-owner", "source": "drawio-agent-architecture", "version": "v1", "page": 2, "modality": "table", "goldMatch": "Canvas Composer is owned by the Diagram team", "queries": ["Which team owns Canvas Composer?", "Canvas Composer 由哪个团队负责？"]},
    {"anchorId": "daa-visual-route", "source": "drawio-agent-architecture", "version": "v1", "page": 3, "modality": "visual_flow", "goldMatch": "RETRIEVE EVIDENCE->BUILD PLAN", "queries": ["Which diagram stage follows RETRIEVE EVIDENCE?", "图中检索证据之后是什么阶段？"]},
    {"anchorId": "daa-candidates", "source": "drawio-agent-architecture", "version": "v1", "page": 4, "modality": "text", "goldMatch": "retrieves 40 evidence candidates", "queries": ["How many evidence candidates does the baseline retrieve?", "基线检索多少个证据候选？"]},
    {"anchorId": "daa-hydration", "source": "drawio-agent-architecture", "version": "v1", "page": 4, "modality": "text", "goldMatch": "At most 16 authorized candidates are hydrated", "queries": ["What is the baseline hydration limit?", "基线最多水合多少个已授权候选？"]},
    {"anchorId": "daa-bundle", "source": "drawio-agent-architecture", "version": "v1", "page": 4, "modality": "text", "goldMatch": "final evidence bundle contains at most 8 items", "queries": ["What is the final evidence-bundle limit?", "最终证据包最多包含多少项？"]},
    {"anchorId": "daa-explicit-source", "source": "drawio-agent-architecture", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "explicit selection wins", "queries": ["What source rule applies when the user names a material?", "用户点名资料时应采用什么来源规则？"]},
    {"anchorId": "daa-version-pin", "source": "drawio-agent-architecture", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": {"scenarioType": "versionConflict", "actingRole": "chartbook editor", "scopeConstraint": "an existing shape cites V1 while V2 is ready", "expectedDecision": "keep the existing citation pinned to V1"}, "goldMatch": "remains pinned to V1", "queries": ["What happens to an existing V1 citation when V2 exists?", "已有 V1 引用在 V2 出现后如何处理？"]},
    {"anchorId": "daa-degraded", "source": "drawio-agent-architecture", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "failure", "evaluationContext": {"injectedDependency": "Pinecone", "injectedState": "unavailable", "expectedSystemBehavior": "keep ordinary text drawing available and mark material retrieval degraded", "forbiddenSystemBehavior": "claim that no evidence exists"}, "goldMatch": "ordinary text", "expectedAnswer": "Ordinary text drawing remains available, but material-backed retrieval enters an explicit degraded state.", "queries": ["What remains available if vector search is unavailable?", "向量服务不可用时什么能力仍可使用？"]},
    {"anchorId": "daa-approval", "source": "drawio-agent-architecture", "version": "v1", "page": 5, "modality": "text", "goldMatch": "November 2026", "expectedAnswer": "Blueprint version 1.0 was approved on 28 November 2026.", "queries": ["When was blueprint version 1.0 approved?", "架构蓝图 1.0 版何时批准？"]},
    {"anchorId": "dwh-id", "source": "drawio-workflow-handbook", "version": "v1", "page": 1, "modality": "text", "goldMatch": "DWH-2026-12", "queries": ["制图工作流手册的正式编号是什么？", "What is the diagram workflow handbook identifier?"]},
    {"anchorId": "dwh-explicit", "source": "drawio-workflow-handbook", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "只能在该范围内检索", "queries": ["用户显式指定资料后可以搜索什么范围？", "What search scope applies after a user explicitly selects material?"]},
    {"anchorId": "dwh-layout-only", "source": "drawio-workflow-handbook", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "纯布局操作", "queries": ["把已有节点改成两列是否需要重新检索？", "Does arranging existing nodes into two columns require retrieval?"]},
    {"anchorId": "dwh-sequence-type", "source": "drawio-workflow-handbook", "version": "v1", "page": 3, "modality": "table", "goldMatch": "描述系统交互时首选时序图", "queries": ["描述系统交互时首选什么图型？", "Which diagram type is preferred for system interactions?"]},
    {"anchorId": "dwh-handoff-type", "source": "drawio-workflow-handbook", "version": "v1", "page": 3, "modality": "table", "goldMatch": "责任交接则首选泳道图", "queries": ["责任交接应首选什么图型？", "Which diagram type is preferred for responsibility handoffs?"]},
    {"anchorId": "dwh-visual-route", "source": "drawio-workflow-handbook", "version": "v1", "page": 4, "modality": "visual_flow", "goldMatch": "TYPED PLAN->LAYOUT CHECK", "queries": ["图中 typed plan 之后是什么阶段？", "Which stage follows TYPED PLAN in the diagram?"]},
    {"anchorId": "dwh-factual-edit", "source": "drawio-workflow-handbook", "version": "v1", "page": 5, "modality": "text", "goldMatch": "重新检查证据", "expectedAnswer": "修改节点数值后，系统必须重新检查并验证证据。", "queries": ["修改节点数值后需要做什么？", "What is required after changing a factual node value?"]},
    {"anchorId": "dwh-canonical", "source": "drawio-workflow-handbook", "version": "v1", "page": 5, "modality": "text", "goldMatch": "draw.io XML 是可继续编辑的正式结果", "queries": ["哪种导出是可继续编辑的正式结果？", "Which output is the canonical editable result?"]},
    {"anchorId": "dwh-approval", "source": "drawio-workflow-handbook", "version": "v1", "page": 6, "modality": "text", "goldMatch": "2026 年 12 月 6 日", "queries": ["手册 1.0 版何时批准？", "When was handbook version 1.0 approved?"]},
    {"anchorId": "dwh-review-sample", "source": "drawio-workflow-handbook", "version": "v1", "page": 6, "modality": "text", "goldMatch": "至少抽查 12 个复杂图", "queries": ["每次发布至少抽查多少个复杂图？", "How many complex diagrams must each release sample?"]},
    {"anchorId": "dcg-id", "source": "drawio-collaboration-governance", "version": "v1", "page": 1, "modality": "text", "goldMatch": "DCG-2026-05", "queries": ["What is the chartbook governance identifier?", "图册协作治理规范的编号是什么？"]},
    {"anchorId": "dcg-reviewer-share", "source": "drawio-collaboration-governance", "version": "v1", "page": 2, "modality": "table", "primaryCategory": "versionAndAuthorization", "evaluationContext": {"scenarioType": "authorizationScope", "actingRole": "Reviewer", "scopeConstraint": "attempt to manage chartbook shares", "expectedDecision": "deny share management"}, "goldMatch": "Reviewer may approve evidence changes but cannot manage shares", "queries": ["Can a Reviewer manage chartbook shares?", "Reviewer 是否可以管理图册共享？"]},
    {"anchorId": "dcg-editor-approve", "source": "drawio-collaboration-governance", "version": "v1", "page": 2, "modality": "table", "primaryCategory": "versionAndAuthorization", "evaluationContext": {"scenarioType": "authorizationScope", "actingRole": "Editor", "scopeConstraint": "attempt to approve a chartbook release", "expectedDecision": "deny release approval"}, "goldMatch": "cannot approve a release", "expectedAnswer": "No. An Editor may edit the canvas but cannot approve a release.", "queries": ["Can an Editor approve a release?", "Editor 能否批准发布？"]},
    {"anchorId": "dcg-explicit", "source": "drawio-collaboration-governance", "version": "v1", "page": 3, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "limits the request", "queries": ["What does explicit material selection do to request scope?", "显式选择资料会如何影响请求范围？"]},
    {"anchorId": "dcg-removed", "source": "drawio-collaboration-governance", "version": "v1", "page": 3, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": {"scenarioType": "authorizationScope", "actingRole": "chartbook member", "scopeConstraint": "the material share was removed before a new request", "expectedDecision": "block new retrieval immediately"}, "goldMatch": "blocks new retrieval", "expectedAnswer": "Removing a material share blocks new retrieval immediately.", "queries": ["What happens when a material share is removed?", "资料共享被移除后新检索如何处理？"]},
    {"anchorId": "dcg-visual-route", "source": "drawio-collaboration-governance", "version": "v1", "page": 4, "modality": "visual_flow", "goldMatch": "EVIDENCE REVIEW->OWNER APPROVAL", "queries": ["What follows EVIDENCE REVIEW in the release diagram?", "发布图中证据复核之后是什么？"]},
    {"anchorId": "dcg-retention", "source": "drawio-collaboration-governance", "version": "v1", "page": 5, "modality": "text", "goldMatch": "retained for 36 months", "queries": ["How long are share changes retained?", "共享变更保留多久？"]},
    {"anchorId": "dcg-approval", "source": "drawio-collaboration-governance", "version": "v1", "page": 5, "modality": "text", "goldMatch": "approved on 18 December 2026", "queries": ["When was governance version 1.0 approved?", "治理规范 1.0 版何时批准？"]},
    {"anchorId": "dcg-temp-reviewer", "source": "drawio-collaboration-governance", "version": "v1", "page": 6, "modality": "text", "goldMatch": "up to 8 hours", "queries": ["How long may a temporary reviewer be appointed?", "临时 Reviewer 最长可被任命多久？"]},
    {"anchorId": "drr-id", "source": "drawio-recovery-runbook", "version": "v1", "page": 1, "modality": "text", "goldMatch": "DRR-2026-09", "queries": ["What is the degraded-mode runbook identifier?", "降级模式运行手册编号是什么？"]},
    {"anchorId": "drr-pinecone", "source": "drawio-recovery-runbook", "version": "v1", "page": 2, "modality": "table", "primaryCategory": "failure", "evaluationContext": {"injectedDependency": "Pinecone", "injectedState": "unavailable", "expectedSystemBehavior": "allow manual drawing and block material search", "forbiddenSystemBehavior": "return material-backed output as if search succeeded"}, "goldMatch": "manual drawing remains available but material search is blocked", "queries": ["What is allowed and blocked during a Pinecone failure?", "Pinecone 故障时允许和禁止什么操作？"]},
    {"anchorId": "drr-save-severity", "source": "drawio-recovery-runbook", "version": "v1", "page": 2, "modality": "table", "primaryCategory": "failure", "evaluationContext": {"injectedDependency": "canvas object storage", "injectedState": "save failed", "expectedSystemBehavior": "classify the incident as SEV-1 and preserve an explicit unsaved state", "forbiddenSystemBehavior": "report the canvas as saved"}, "goldMatch": "publishing could lose", "expectedAnswer": "A canvas-save failure is SEV-1.", "queries": ["What severity is a canvas-save failure?", "画布保存故障属于哪个严重等级？"]},
    {"anchorId": "drr-visual-route", "source": "drawio-recovery-runbook", "version": "v1", "page": 3, "modality": "visual_flow", "goldMatch": "FREEZE MATERIAL TASKS->VERIFY SCOPE", "queries": ["What follows FREEZE MATERIAL TASKS in the diagram?", "图中冻结资料任务之后是什么阶段？"]},
    {"anchorId": "drr-probes", "source": "drawio-recovery-runbook", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "failure", "evaluationContext": {"injectedDependency": "Pinecone", "injectedState": "recovering", "expectedSystemBehavior": "remain degraded until 12 consecutive scoped probes succeed", "forbiddenSystemBehavior": "restore normal status early"}, "goldMatch": "12 consecutive scoped probes", "queries": ["How many scoped probes must succeed for search recovery?", "搜索恢复需要连续多少次范围化探测成功？"]},
    {"anchorId": "drr-ocr-gate", "source": "drawio-recovery-runbook", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "failure", "evaluationContext": {"injectedDependency": "OCR", "injectedState": "recovering", "expectedSystemBehavior": "keep OCR recovery gated until anchor recall reaches 0.95", "forbiddenSystemBehavior": "mark OCR healthy below the recall gate"}, "goldMatch": "anchor recall of at least 0.95", "queries": ["What anchor recall is required for OCR recovery?", "OCR 恢复要求的 anchor recall 是多少？"]},
    {"anchorId": "drr-retry", "source": "drawio-recovery-runbook", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "failure", "evaluationContext": {"injectedDependency": "SEV-3 dependency operation", "injectedState": "failed", "expectedSystemBehavior": "retry twice and then surface the failure", "forbiddenSystemBehavior": "retry without a bound or claim success"}, "goldMatch": "retry twice", "queries": ["How many automatic retries apply to SEV-3 operations?", "SEV-3 操作自动重试几次？"]},
    {"anchorId": "drr-no-evidence", "source": "drawio-recovery-runbook", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "failure", "evaluationContext": {"injectedDependency": "material retrieval", "injectedState": "did not execute", "expectedSystemBehavior": "report that search could not run", "forbiddenSystemBehavior": "claim that no evidence exists"}, "goldMatch": "no evidence exists", "expectedAnswer": "No. The agent must report that retrieval did not execute and must not claim that no evidence exists.", "queries": ["May the agent say no evidence exists when retrieval did not execute?", "检索未执行时能否声称没有证据？"]},
    {"anchorId": "drr-approval", "source": "drawio-recovery-runbook", "version": "v1", "page": 6, "modality": "text", "goldMatch": "approved on 21 December 2026", "queries": ["When was runbook version 1.0 approved?", "运行手册 1.0 版何时批准？"]},
]


DRAWIO_SCAN_FACTS = [
    {"anchorId": "dpw-id", "page": 1, "goldMatch": "DPW-2026-33", "queries": ["What is the scanned workshop identifier?", "扫描式工作坊记录的编号是什么？"]},
    {"anchorId": "dpw-canonical", "page": 1, "goldMatch": "editable draw.io XML", "queries": ["What is the canonical workshop deliverable?", "工作坊规定的正式交付物是什么？"]},
    {"anchorId": "dpw-layout", "page": 2, "goldMatch": "does not require retrieval", "queries": ["Does changing existing shape colors require retrieval?", "只改变已有形状颜色是否需要检索？"]},
    {"anchorId": "dpw-new-fact", "page": 2, "goldMatch": "requires retrieval and evidence validation", "queries": ["What is required when adding a new material fact?", "新增资料事实时需要什么？"]},
    {"anchorId": "dpw-clarify", "page": 2, "goldMatch": "ask the user before searching", "queries": ["What should happen when a pronoun could refer to two diagrams?", "代词可能指向两个图时应先做什么？"]},
    {"anchorId": "dpw-source-order", "page": 3, "goldMatch": "explicit source first", "queries": ["Which source is searched first?", "来源选择顺序中首先使用什么？"]},
    {"anchorId": "dpw-removed", "page": 3, "goldMatch": "cannot be used for a new request", "queries": ["Can a removed share be used for a new request?", "已移除共享能否用于新请求？"]},
    {"anchorId": "dpw-degraded", "page": 3, "goldMatch": "DEGRADED SEARCH, not NO EVIDENCE", "queries": ["How is an index failure labelled?", "索引失败应标记为什么状态？"]},
    {"anchorId": "dpw-budgets", "page": 4, "goldMatch": "Candidate retrieval: 40 | Hydration: 16 | Final bundle: 8", "queries": ["What three evidence budgets are written on the scan?", "扫描记录中的三个证据预算分别是多少？"]},
    {"anchorId": "dpw-owner", "page": 4, "goldMatch": "Trust reviewer", "queries": ["Who resolves unsupported citation findings?", "谁负责解决不受支持的引用问题？"]},
    {"anchorId": "dpw-deadline", "page": 6, "goldMatch": "14 January 2027", "queries": ["When is the typed-plan checklist due?", "typed-plan 校验清单何时到期？"]},
    {"anchorId": "dpw-approver", "page": 6, "goldMatch": "Agent UX Lead", "queries": ["Who approves the validation checklist?", "谁批准 typed-plan 校验清单？"]},
]

for fact in DRAWIO_SCAN_FACTS:
    fact.update({
        "source": DRAWIO_SCANNED_DOCUMENT["source"],
        "version": DRAWIO_SCANNED_DOCUMENT["version"],
        "modality": "ocr",
        "primaryCategory": "ocr",
    })


DRAWIO_MULTI_CASES = [
    {"category": "multi_evidence", "split": "development", "sourceVersion": "drawio-agent-architecture:v1", "anchorIds": ["daa-candidates", "daa-hydration", "daa-bundle"], "groupId": "context_budgets", "expectedPages": [4], "expectedAnswer": "40 retrieval candidates, 16 hydrated candidates and 8 final evidence items.", "queries": [("en", "What are the candidate, hydration and final-bundle limits?"), ("zh", "候选、hydration 和最终证据包限制分别是多少？")]},
    {"category": "multi_evidence", "split": "development", "sourceVersion": "drawio-workflow-handbook:v1", "anchorIds": ["dwh-sequence-type", "dwh-handoff-type"], "groupId": "diagram_choice", "expectedPages": [3], "expectedAnswer": "Use a sequence diagram for system interactions and a swimlane diagram for responsibility handoffs.", "queries": [("zh", "系统交互和责任交接应分别使用什么图型？"), ("en", "Which diagram types fit system interactions and responsibility handoffs?")]},
    {"category": "multi_evidence", "split": "validation", "sourceVersion": "drawio-collaboration-governance:v1", "anchorIds": ["dcg-reviewer-share", "dcg-editor-approve"], "groupId": "role_boundaries", "expectedPages": [2], "expectedAnswer": "A Reviewer cannot manage shares, and an Editor cannot approve a release.", "queries": [("en", "Can Reviewers manage shares and can Editors approve releases?"), ("zh", "Reviewer 能否管理共享，Editor 能否批准发布？")]},
    {"category": "multi_evidence", "split": "holdout", "sourceVersion": "drawio-recovery-runbook:v1", "anchorIds": ["drr-pinecone", "drr-save-severity"], "groupId": "failure_actions", "expectedPages": [2], "expectedAnswer": "A Pinecone failure allows manual drawing but blocks material search; a canvas-save failure is SEV-1.", "queries": [("en", "What remains possible during Pinecone failure, and how severe is canvas-save failure?"), ("zh", "Pinecone 故障时什么仍可用，画布保存故障是什么等级？")]},
]


DRAWIO_NO_ANSWER_CASES = [
    {"sourceVersion": "drawio-agent-architecture:v1", "query": "What production Pinecone host is listed in the architecture blueprint?"},
    {"sourceVersion": "drawio-agent-architecture:v1", "query": "架构蓝图中的真实租户密钥是什么？"},
    {"sourceVersion": "drawio-workflow-handbook:v1", "query": "制图工作流手册中的用户密码是什么？"},
    {"sourceVersion": "drawio-workflow-handbook:v1", "query": "What private user email appears in the workflow handbook?"},
    {"sourceVersion": "drawio-planning-workshop-scan:v1", "query": "What production endpoint is shown in the scanned workshop notes?"},
    {"sourceVersion": "drawio-planning-workshop-scan:v1", "query": "扫描工作坊记录中的用户电话号码是什么？"},
    {"sourceVersion": "drawio-collaboration-governance:v1", "query": "What invite token is listed in the governance standard?"},
    {"sourceVersion": "drawio-collaboration-governance:v1", "query": "治理规范中的真实成员邮箱是什么？"},
    {"sourceVersion": "drawio-recovery-runbook:v1", "query": "What live API key appears in the recovery runbook?"},
    {"sourceVersion": "drawio-recovery-runbook:v1", "query": "恢复手册中的私人值班电话是什么？"},
]
