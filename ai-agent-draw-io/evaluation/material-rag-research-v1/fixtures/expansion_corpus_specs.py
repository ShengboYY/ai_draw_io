"""Expansion documents to grow the core set from 240 to 450 (every category >= 50).

Same design as ``scenario_corpus_specs.py``: dense, paper-style real-domain
material a user would upload for grounded diagramming. Each family contributes
across categories, with emphasis on the under-supplied rule categories
(retrievalDecision / versionAndAuthorization / failure). Exact balancing to the
V2 targets is done by the ILP-driven ``query-selection.json``.
"""

from __future__ import annotations


def _v(scenario, role, scope, decision):
    return {"scenarioType": scenario, "actingRole": role, "scopeConstraint": scope,
            "expectedDecision": decision}


def _f(dep, state, expected, forbidden):
    return {"injectedDependency": dep, "injectedState": state,
            "expectedSystemBehavior": expected, "forbiddenSystemBehavior": forbidden}


EXPANSION_DIGITAL_DOCUMENTS = [
    {
        "source": "expansion-datacenter-change",
        "documentFamily": "datacenter-capacity-change",
        "version": "v1",
        "split": "development",
        "language": "zh",
        "filename": "expansion-datacenter-capacity-change-v1.pdf",
        "template": "paper",
        "footerLabel": "Datacenter Capacity & Change",
        "bodyFontSize": 9.8,
        "bodyLeading": 13.8,
        "pages": [
            {
                "title": "数据中心容量与变更管理规范",
                "subtitle": "DCC-2026-40 | 面向图形化评审的运维设计资料",
                "layout": "columns",
                "sections": [
                    ("适用范围", "本规范定义数据中心的容量评估、变更发布与降级处置流程,供架构评审与图形化梳理使用。正式编号为 DCC-2026-40;DCC-2026-04 是早期演练脚本,不属于本版本。"),
                    ("对象边界", "规范覆盖资源调度、变更编排、容量水位与故障降级,不涉及机房物理安防与电力一次设备。图形化输出必须区分「平台内组件」与「外部依赖」,不能把外部云服务画入平台内部信任域。"),
                    ("阅读约定", "「变更」指对生产配置的受控修改,「事件」指运行中触发的异常,两者流程不同不可混用。带编号的变更单与资源池对应唯一事实,相近编号常来自历史演练。"),
                    ("证据原则", "水位阈值、发布批次与降级触发条件以平台落库记录为准,人工描述只能解释异常。承载事实的图形节点必须可追溯到本规范的页码与区域。"),
                    ("文档结构", "第 1 章给出组件职责,第 2、3 章以架构图与发布时序图呈现关系,第 4 章说明检索决策,第 5 章给出版本、容量阈值与降级规则,第 6 章记录限制与否定证据。"),
                    ("拒答边界", "本规范不包含机房门禁密码、堡垒机口令、云账号密钥或值班人员私人电话。凡请求上述信息的问题都必须拒答。"),
                ],
            },
            {
                "title": "1. 组件职责与信任域",
                "subtitle": "原生表格:调度链路组件映射",
                "sections": [
                    ("职责原则", "职责按决策归属划分。容量评估决定是否放行扩容,发布编排只执行已批准批次,不得自行扩大变更范围。事件响应的降级结论不能被发布环节覆盖。"),
                ],
                "table": {
                    "headers": ["组件", "职责", "输入", "输出", "负责团队"],
                    "rows": [
                        ["资源目录", "资源清单", "上报数据", "可用资源", "平台组"],
                        ["容量评估", "水位判定", "可用资源", "扩容结论", "容量组"],
                        ["发布编排", "批次执行", "扩容结论", "变更批次", "发布组"],
                        ["事件响应", "降级决策", "监控告警", "降级指令", "值班组"],
                        ["配置审计", "记录核对", "变更回执", "审计结果", "合规组"],
                    ],
                    "colWidths": [78, 84, 84, 84, 82],
                },
                "afterSections": [
                    ("读取提示", "发布编排由发布组负责,只执行已批准批次。容量评估的扩容结论不能被发布环节覆盖;配置审计可以标记差异,但不能修改容量结论。"),
                    ("画图提示", "把本表画成调度链路图时,应按「决策方指向执行方」连线;容量评估的输出是发布编排的输入,任一环节不得跳过容量评估直接扩容。"),
                ],
            },
            {
                "title": "2. 请求到发布架构",
                "subtitle": "仅存在于像素中的架构关系",
                "sections": [
                    ("图示范围", "从资源评估到变更落地的组件顺序只在图 2 中给出,正文不复述节点先后。视觉类问题必须查看图形并保持箭头方向,不能仅凭正文名词推断链路。"),
                    ("失败边界", "容量结论缺失时,发布编排保持等待,不得凭旧评估继续下发变更批次。"),
                    ("回流关系", "被容量评估否决的扩容不进入发布,而是回到资源目录补充数据;该回流关系以图形为准。"),
                    ("解读提示", "阅读该图应先确认资源来源与落地终点,再核对每步方向;误读为无向连接会使降级判断出错。"),
                ],
                "diagram": {
                    "filename": "expansion-datacenter-release-route.png",
                    "title": "CAPACITY CHANGE ROUTE",
                    "nodes": ["RESOURCE CATALOG", "CAPACITY CHECK", "CHANGE ORCHESTRATE", "APPLY"],
                    "edgeLabels": ["listed", "approved", "batched"],
                },
            },
            {
                "title": "3. 变更发布时序",
                "subtitle": "仅存在于像素中的时序关系",
                "sections": [
                    ("时序范围", "变更批次的确认顺序只在图 3 中给出,正文不复述消息先后。时序类问题必须依据图形判断方向,而不是套用架构图顺序。"),
                    ("确认衔接", "只有在收到健康检查回执并通过后才进入下一批次;未通过时保持当前批次,不得自动全量发布。"),
                    ("超时处理", "健康回执超时按未通过处理,进入观察与人工确认,而不是默认继续。默认继续是必须避免的错误路径。"),
                    ("终态边界", "只有末批次确认通过后才标记变更完成;中间批次通过不等于整体完成。"),
                ],
                "diagram": {
                    "filename": "expansion-datacenter-rollout-sequence.png",
                    "title": "CHANGE ROLLOUT SEQUENCE",
                    "nodes": ["BATCH START", "HEALTH CHECK", "PROMOTE", "COMPLETE"],
                    "edgeLabels": ["deploy", "passed", "advanced"],
                },
            },
            {
                "title": "4. 检索决策与来源范围",
                "subtitle": "双栏:何时检索、显式来源与澄清",
                "layout": "columns",
                "sections": [
                    ("是否检索", "把已有调度链路图重新排布或调整颜色属于纯图形操作,不需要重新检索。新增容量事实、比较水位或解释外部依赖关系时,必须检索并验证证据。"),
                    ("显式来源", "用户点名某个变更单或资源池资料时,只能在该范围内检索;若该资料缺少目标事实,应说明证据不足,而不是改用最新资料补全。"),
                    ("来源顺序", "没有显式来源时,先搜索当前图册允许共享的运维资料,再在开启自动检索后扩大到更广授权资料,并把范围变化写入回答元数据。"),
                    ("阶段限额", "基线检索 40 个证据候选,最多水合 16 个已授权候选,最终证据包至多 8 项。演练材料中出现的 24 不是基线上限。"),
                    ("澄清条件", "当「这次变更」「这个池子」可能对应多个资源池时,先请求澄清,不能用相似度最高的结果替代用户选择。"),
                    ("降级说明", "检索服务不可用时,普通图形绘制仍可进行,但运维资料检索进入显式降级状态,不得声称「没有相关变更」。"),
                ],
            },
            {
                "title": "5. 版本、容量阈值与降级规则",
                "subtitle": "版本固定、水位阈值与被拒草案",
                "sections": [
                    ("角色分离", "编辑者修改画布,复核者处理证据问题,所有者管理共享与发布。每个动作按所需角色单独校验,获得一种权限并不自动获得其他权限。"),
                ],
                "table": {
                    "headers": ["角色", "编辑画布", "使用运维资料", "批准变更", "管理共享"],
                    "rows": [
                        ["查看者", "否", "获授权时可", "否", "否"],
                        ["编辑者", "是", "获授权时可", "否", "否"],
                        ["复核者", "评论", "获授权时可", "建议", "否"],
                        ["运维所有者", "是", "是", "是", "是"],
                    ],
                    "colWidths": [78, 74, 96, 76, 84],
                },
                "afterSections": [
                    ("版本审批", "本规范 1.0 版于 2026 年 11 月 28 日由运维治理委员会批准。10 月草案曾允许水位超过 85% 时自动扩容,评审认为风险过高并删除该规则。已固定引用某一版本容量基线的图形在新版本出现后仍固定引用原版本。"),
                    ("容量阈值", "核心资源池的告警水位为 75%,人工复核水位为 85%,硬上限为 95%。单次瞬时超限只记录观察,连续三个采样周期超过 85% 才进入人工复核。"),
                    ("降级触发", "当依赖的对象存储不可用时,允许继续编辑已缓存的画布,但阻止加载新证据,并且不得把降级状态伪装成检索成功。"),
                ],
            },
            {
                "title": "6. 限制、否定证据与引用要求",
                "subtitle": "双栏:适用限制、无答案边界与引用规范",
                "layout": "columns",
                "sections": [
                    ("适用限制", "本规范描述容量与变更流程,不涵盖具体云厂商的计费模型、硬件保修条款或安全合规审计细则。跨区域差异需要单独评估。"),
                    ("易混淆项", "文中水位 75%/85%/95% 与证据包上限 8、演练材料的 24 都容易混淆;回答必须锁定对象与场景。"),
                    ("无答案边界", "规范不包含机房门禁密码、堡垒机口令、云账号密钥或值班电话,系统对这些问题必须说明证据不足。"),
                    ("版本引用", "涉及版本固定或审批的回答必须引用第 5 章批准版本,并在提到 10 月草案时明确其为已否决内容。"),
                    ("图形引用", "调度链路引用第 2 章架构图,发布顺序引用第 3 章时序图,二者不可互换;组件职责引用第 1 章表格,降级规则引用第 5 章。"),
                    ("完成标准", "一次变更图形化交付完成,需要可编辑画布、通过的布局检查、事实节点的证据绑定,以及没有未解决的版本或权限冲突。仅有导出预览图不算完成。"),
                ],
            },
        ],
    },
]


EXPANSION_FACTS = [
    {"anchorId": "dcc-id", "source": "expansion-datacenter-change", "version": "v1", "page": 1,
     "modality": "text", "goldMatch": "DCC-2026-40",
     "queries": ["数据中心容量与变更管理规范的编号是什么?"]},
    {"anchorId": "dcc-orchestrate-owner", "source": "expansion-datacenter-change", "version": "v1", "page": 2,
     "modality": "table", "goldMatch": "发布编排由发布组负责",
     "queries": ["发布编排由哪个团队负责?"]},
    {"anchorId": "dcc-arch-route", "source": "expansion-datacenter-change", "version": "v1", "page": 3,
     "modality": "visual_flow", "goldMatch": "CAPACITY CHECK->CHANGE ORCHESTRATE",
     "queries": ["架构图中容量检查之后是哪个组件?", "Which component follows CAPACITY CHECK in the route?"]},
    {"anchorId": "dcc-arch-apply", "source": "expansion-datacenter-change", "version": "v1", "page": 3,
     "modality": "visual_flow", "goldMatch": "CHANGE ORCHESTRATE->APPLY",
     "queries": ["架构图中变更编排之后是哪个环节?"]},
    {"anchorId": "dcc-seq-promote", "source": "expansion-datacenter-change", "version": "v1", "page": 4,
     "modality": "visual_flow", "goldMatch": "HEALTH CHECK->PROMOTE",
     "queries": ["时序图中健康检查通过之后是什么?", "What follows HEALTH CHECK in the rollout sequence?"]},
    {"anchorId": "dcc-layout-retrieve", "source": "expansion-datacenter-change", "version": "v1", "page": 5,
     "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "属于纯图形操作,不需要重新检索",
     "queries": ["把已有调度链路图重新排布是否需要重新检索?"]},
    {"anchorId": "dcc-explicit-source", "source": "expansion-datacenter-change", "version": "v1", "page": 5,
     "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "只能在该范围内检索",
     "queries": ["用户点名某个变更单后,检索范围应如何限制?", "How is retrieval scope limited after a specific change record is named?"]},
    {"anchorId": "dcc-clarify", "source": "expansion-datacenter-change", "version": "v1", "page": 5,
     "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "先请求澄清",
     "queries": ["当「这个池子」可能指多个资源池时应先做什么?"]},
    {"anchorId": "dcc-version-pin", "source": "expansion-datacenter-change", "version": "v1", "page": 6,
     "modality": "table", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionConflict", "图册编辑者", "已有图形引用某容量基线版本,新版本已出现", "保持图形固定引用原版本"),
     "goldMatch": "仍固定引用原版本",
     "expectedAnswer": "保持固定在原版本;已引用原容量基线版本的图形不因新版本出现而改写。",
     "queries": ["已引用某容量基线版本的图形在新版本出现后如何处理?"]},
    {"anchorId": "dcc-draft-reject", "source": "expansion-datacenter-change", "version": "v1", "page": 6,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("draftRejected", "运维治理委员会", "10 月草案允许水位超过 85% 自动扩容", "使用批准版本,不自动扩容"),
     "goldMatch": "评审认为风险过高并删除该规则",
     "expectedAnswer": "10 月草案的自动扩容规则被否决;不得在 85% 自动扩容。",
     "queries": ["10 月草案的自动扩容规则是否被采纳?"]},
    {"anchorId": "dcc-approve-role", "source": "expansion-datacenter-change", "version": "v1", "page": 6,
     "modality": "table", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("authorizationScope", "复核者", "尝试单独批准变更", "拒绝单独批准,仅可给出建议"),
     "goldMatch": "复核者",
     "expectedAnswer": "不能。复核者只能给出建议,变更由运维所有者批准。",
     "queries": ["复核者能否单独批准变更?"]},
    {"anchorId": "dcc-degrade", "source": "expansion-datacenter-change", "version": "v1", "page": 6,
     "modality": "text", "primaryCategory": "failure",
     "evaluationContext": _f("对象存储", "不可用", "允许编辑已缓存画布并阻止加载新证据", "把降级状态伪装成检索成功"),
     "goldMatch": "不得把降级状态伪装成检索成功",
     "expectedAnswer": "允许编辑已缓存画布,阻止加载新证据,且不得伪装成检索成功。",
     "queries": ["对象存储不可用时应如何处置?", "What is allowed and forbidden when object storage is unavailable?"]},
    {"anchorId": "dcc-threshold", "source": "expansion-datacenter-change", "version": "v1", "page": 6,
     "modality": "text", "primaryCategory": "failure",
     "evaluationContext": _f("核心资源池水位", "连续三个采样周期超过 85%", "进入人工复核", "凭单次瞬时超限直接扩容"),
     "goldMatch": "连续三个采样周期超过 85% 才进入人工复核",
     "expectedAnswer": "连续三个采样周期超过 85% 才进入人工复核,单次瞬时超限只记录观察。",
     "queries": ["核心资源池在什么条件下进入人工复核?"]},
]


EXPANSION_MULTI_CASES = [
    {"category": "multi_evidence", "split": "development",
     "sourceVersion": "expansion-datacenter-change:v1",
     "anchorIds": ["dcc-arch-route", "dcc-seq-promote"],
     "groupId": "route_and_sequence", "expectedPages": [3, 4],
     "expectedAnswer": "架构路径上 CAPACITY CHECK 之后进入 CHANGE ORCHESTRATE;时序上 HEALTH CHECK 通过后进入 PROMOTE。",
     "queries": [("zh", "请分别说明架构中容量检查之后、时序中健康检查通过之后各进入哪个环节?")]},
]


EXPANSION_NO_ANSWER_CASES = [
    {"sourceVersion": "expansion-datacenter-change:v1", "query": "规范中机房堡垒机的登录口令是什么?"},
    {"sourceVersion": "expansion-datacenter-change:v1", "query": "What cloud account access key is listed in the datacenter standard?"},
]


# ---- Batch 2: microservice observability (validation) + supply-chain (holdout) ----
EXPANSION_DIGITAL_DOCUMENTS += [
    {
        "source": "expansion-observability",
        "documentFamily": "microservice-observability",
        "version": "v1",
        "split": "validation",
        "language": "mixed",
        "filename": "expansion-microservice-observability-v1.pdf",
        "template": "paper",
        "footerLabel": "Microservice Observability",
        "bodyFontSize": 9.8,
        "bodyLeading": 13.8,
        "pages": [
            {
                "title": "微服务可观测性与告警运行手册",
                "subtitle": "MSO-2026-33 | telemetry, alerting and on-call runbook",
                "layout": "columns",
                "sections": [
                    ("适用范围", "本手册规范微服务的指标采集、告警分级、根因定位与值班处置流程,供图形化梳理使用。正式编号为 MSO-2026-33;MSO-2026-03 是压测脚本,不属于本版本。"),
                    ("Scope boundary", "The runbook covers metric collection, alert routing, root-cause triage and on-call handling. It does not define business SLAs, billing or datacenter power. Graphical output must separate platform components from external dependencies."),
                    ("阅读约定", "「指标」指采集到的时序数据,「告警」指越过阈值触发的事件,两者不可混用。带编号的告警规则与服务对应唯一事实,相近编号常来自历史演练。"),
                    ("Evidence principle", "Alert thresholds, routing rules and degraded-mode behavior follow the platform record. A graphical node carrying a fact must trace back to a page and region here."),
                    ("拒答边界", "本手册不包含告警平台管理员口令、值班人员私人电话、云监控 API 密钥或数据库连接串。凡请求上述信息的问题都必须拒答。"),
                ],
            },
            {
                "title": "1. 采集链路组件",
                "subtitle": "Native table: telemetry pipeline",
                "sections": [
                    ("职责原则", "职责按决策归属划分。告警评估决定是否触发,路由分发只投递已触发告警,不得自行升级级别。根因分析的结论不能被分发环节覆盖。"),
                ],
                "table": {
                    "headers": ["组件", "职责", "输入", "输出", "负责团队"],
                    "rows": [
                        ["采集代理", "指标采集", "服务埋点", "时序数据", "平台组"],
                        ["告警评估", "阈值判定", "时序数据", "告警事件", "SRE 组"],
                        ["路由分发", "事件投递", "告警事件", "通知任务", "值班组"],
                        ["根因分析", "关联定位", "告警事件", "根因结论", "SRE 组"],
                        ["复盘归档", "记录核对", "处置回执", "复盘报告", "质量组"],
                    ],
                    "colWidths": [78, 84, 84, 84, 82],
                },
                "afterSections": [
                    ("读取提示", "路由分发由值班组负责,只投递已触发告警。告警评估的触发结论不能被分发环节覆盖;复盘归档可以标记差异,但不能修改告警结论。"),
                ],
            },
            {
                "title": "2. 采集到告警架构",
                "subtitle": "仅存在于像素中的架构关系",
                "sections": [
                    ("图示范围", "从采集到告警的组件顺序只在图 2 中给出,正文不复述节点先后。视觉类问题必须查看图形并保持箭头方向。"),
                    ("失败边界", "告警结论缺失时,路由分发保持等待,不得凭旧结论继续投递通知。"),
                    ("解读提示", "阅读该图应先确认采集来源与通知终点,再核对每步方向;误读为无向连接会使根因判断出错。"),
                ],
                "diagram": {
                    "filename": "expansion-observability-route.png",
                    "title": "TELEMETRY ALERT ROUTE",
                    "nodes": ["COLLECT AGENT", "ALERT EVAL", "ROUTE DISPATCH", "NOTIFY"],
                    "edgeLabels": ["scraped", "fired", "routed"],
                },
            },
            {
                "title": "3. 告警处置时序",
                "subtitle": "仅存在于像素中的时序关系",
                "sections": [
                    ("时序范围", "告警确认与升级的顺序只在图 3 中给出,正文不复述消息先后。时序类问题必须依据图形判断方向。"),
                    ("确认衔接", "只有在值班确认并定位根因后才进入升级;未确认时保持当前级别,不得自动升级到最高级。"),
                    ("超时处理", "确认超时按未确认处理,进入自动升级流程而不是静默关闭。静默关闭是必须避免的错误路径。"),
                ],
                "diagram": {
                    "filename": "expansion-observability-sequence.png",
                    "title": "ALERT HANDLING SEQUENCE",
                    "nodes": ["ALERT FIRE", "ACK", "DIAGNOSE", "ESCALATE"],
                    "edgeLabels": ["notified", "acknowledged", "located"],
                },
            },
            {
                "title": "4. 检索决策与来源范围",
                "subtitle": "双栏:何时检索、显式来源与澄清",
                "layout": "columns",
                "sections": [
                    ("是否检索", "把已有采集链路图重新排布或调整颜色属于纯图形操作,不需要重新检索。新增告警事实、比较阈值或解释外部依赖关系时,必须检索并验证证据。"),
                    ("显式来源", "用户点名某个告警规则或服务资料时,只能在该范围内检索;若该资料缺少目标事实,应说明证据不足,而不是改用最新资料补全。"),
                    ("来源顺序", "没有显式来源时,先搜索当前图册允许共享的运维资料,再在开启自动检索后扩大到更广授权资料,并把范围变化写入回答元数据。"),
                    ("阶段限额", "基线检索 40 个证据候选,最多水合 16 个已授权候选,最终证据包至多 8 项。演练材料中的 24 不是基线上限。"),
                    ("降级说明", "检索服务不可用时,普通图形绘制仍可进行,但运维资料检索进入显式降级状态,不得声称「没有相关告警」。"),
                ],
            },
            {
                "title": "5. 版本、SLO 阈值与降级规则",
                "subtitle": "版本固定、告警阈值与被拒草案",
                "sections": [
                    ("角色分离", "编辑者修改画布,复核者处理证据问题,所有者管理共享与发布。每个动作按所需角色单独校验,获得一种权限并不自动获得其他权限。"),
                ],
                "table": {
                    "headers": ["角色", "编辑画布", "使用运维资料", "批准变更", "管理共享"],
                    "rows": [
                        ["查看者", "否", "获授权时可", "否", "否"],
                        ["编辑者", "是", "获授权时可", "否", "否"],
                        ["复核者", "评论", "获授权时可", "建议", "否"],
                        ["SRE 所有者", "是", "是", "是", "是"],
                    ],
                    "colWidths": [78, 74, 96, 76, 84],
                },
                "afterSections": [
                    ("版本审批", "本手册 1.0 版于 2026 年 11 月 22 日由可观测性治理委员会批准。10 月草案曾允许错误率超过阈值时自动重启实例,评审认为风险过高并删除该规则。已固定引用某一版本告警基线的图形在新版本出现后仍固定引用原版本。"),
                    ("SLO 阈值", "核心接口的可用性目标为 99.9%,错误率告警阈值为 1%,P99 延迟阈值为 800 毫秒。单次抖动只记录观察,连续三个采样周期超过阈值才触发人工确认。"),
                    ("降级触发", "当指标采集管道不可用时,允许基于已缓存数据继续绘图,但阻止生成新的告警结论,并且不得把采集中断伪装成「无告警」。"),
                ],
            },
        ],
    },
    {
        "source": "expansion-supply-chain",
        "documentFamily": "supply-chain-fulfillment",
        "version": "v1",
        "split": "holdout",
        "language": "zh",
        "filename": "expansion-supply-chain-fulfillment-v1.pdf",
        "template": "paper",
        "footerLabel": "Supply Chain Fulfillment",
        "bodyFontSize": 9.8,
        "bodyLeading": 13.8,
        "pages": [
            {
                "title": "供应链履约与库存调拨规范",
                "subtitle": "SCF-2026-28 | 面向图形化评审的履约设计资料",
                "layout": "columns",
                "sections": [
                    ("适用范围", "本规范定义订单履约、库存调拨与异常处置流程,供图形化梳理使用。正式编号为 SCF-2026-28;SCF-2026-82 是培训样例,不属于本版本。"),
                    ("对象边界", "规范覆盖履约编排、库存水位、调拨审批与缺货降级,不涉及供应商结算与运输合同条款。图形化输出必须区分「平台内组件」与「外部承运方」。"),
                    ("阅读约定", "「调拨」指仓间库存转移,「异常」指履约中触发的缺货或超卖,两者流程不同不可混用。带编号的调拨单与仓库对应唯一事实。"),
                    ("证据原则", "库存水位、调拨批次与缺货触发条件以平台落库记录为准,人工描述只能解释异常。承载事实的图形节点必须可追溯到本规范的页码与区域。"),
                    ("拒答边界", "本规范不包含承运商结算账号、仓库门禁密码、系统管理员口令或客户身份证号。凡请求上述信息的问题都必须拒答。"),
                ],
            },
            {
                "title": "1. 履约组件与职责",
                "subtitle": "原生表格:履约链路组件映射",
                "sections": [
                    ("职责原则", "职责按决策归属划分。库存评估决定是否放行调拨,履约编排只执行已批准批次,不得自行扩大调拨范围。异常处置的降级结论不能被履约环节覆盖。"),
                ],
                "table": {
                    "headers": ["组件", "职责", "输入", "输出", "负责团队"],
                    "rows": [
                        ["订单网关", "受理与校验", "客户订单", "标准化订单", "接入组"],
                        ["库存评估", "水位判定", "标准化订单", "可履约结论", "库存组"],
                        ["履约编排", "批次执行", "可履约结论", "调拨批次", "履约组"],
                        ["异常处置", "缺货降级", "履约回执", "降级指令", "运营组"],
                        ["对账归档", "记录核对", "调拨回执", "对账结果", "财务组"],
                    ],
                    "colWidths": [78, 84, 84, 84, 82],
                },
                "afterSections": [
                    ("读取提示", "履约编排由履约组负责,只执行已批准批次。库存评估的可履约结论不能被履约环节覆盖;对账归档可以标记差异,但不能修改库存结论。"),
                ],
            },
            {
                "title": "2. 订单到调拨架构",
                "subtitle": "仅存在于像素中的架构关系",
                "sections": [
                    ("图示范围", "从订单到调拨落地的组件顺序只在图 2 中给出,正文不复述节点先后。视觉类问题必须查看图形并保持箭头方向。"),
                    ("失败边界", "可履约结论缺失时,履约编排保持等待,不得凭旧结论继续下发调拨批次。"),
                    ("解读提示", "阅读该图应先确认订单来源与调拨终点,再核对每步方向;误读为无向连接会使缺货降级判断出错。"),
                ],
                "diagram": {
                    "filename": "expansion-supply-chain-route.png",
                    "title": "FULFILLMENT ROUTE",
                    "nodes": ["ORDER GATEWAY", "STOCK CHECK", "FULFILL ORCHESTRATE", "TRANSFER"],
                    "edgeLabels": ["normalized", "approved", "batched"],
                },
            },
            {
                "title": "3. 库存调拨时序",
                "subtitle": "仅存在于像素中的时序关系",
                "sections": [
                    ("时序范围", "调拨批次的确认顺序只在图 3 中给出,正文不复述消息先后。时序类问题必须依据图形判断方向。"),
                    ("确认衔接", "只有在收到到货确认回执并核对后才进入下一批次;未确认时保持当前批次,不得自动全量调拨。"),
                    ("超时处理", "到货回执超时按未确认处理,进入异常处置而不是默认继续。默认继续是必须避免的错误路径。"),
                ],
                "diagram": {
                    "filename": "expansion-supply-chain-sequence.png",
                    "title": "STOCK TRANSFER SEQUENCE",
                    "nodes": ["BATCH START", "ARRIVAL CHECK", "RECONCILE", "COMPLETE"],
                    "edgeLabels": ["dispatch", "confirmed", "matched"],
                },
            },
            {
                "title": "4. 检索决策与来源范围",
                "subtitle": "双栏:何时检索、显式来源与澄清",
                "layout": "columns",
                "sections": [
                    ("是否检索", "把已有履约链路图重新排布或调整颜色属于纯图形操作,不需要重新检索。新增库存事实、比较水位或解释承运关系时,必须检索并验证证据。"),
                    ("显式来源", "用户点名某个调拨单或仓库资料时,只能在该范围内检索;若该资料缺少目标事实,应说明证据不足,而不是改用最新资料补全。"),
                    ("来源顺序", "没有显式来源时,先搜索当前图册允许共享的履约资料,再在开启自动检索后扩大到更广授权资料,并把范围变化写入回答元数据。"),
                    ("阶段限额", "基线检索 40 个证据候选,最多水合 16 个已授权候选,最终证据包至多 8 项。培训材料中的 20 不是基线上限。"),
                    ("澄清条件", "当「这批货」「这个仓」可能对应多个仓库时,先请求澄清,不能用相似度最高的结果替代用户选择。"),
                ],
            },
            {
                "title": "5. 版本、库存阈值与降级规则",
                "subtitle": "版本固定、安全库存与被拒草案",
                "sections": [
                    ("角色分离", "编辑者修改画布,复核者处理证据问题,所有者管理共享与发布。每个动作按所需角色单独校验,获得一种权限并不自动获得其他权限。"),
                ],
                "table": {
                    "headers": ["角色", "编辑画布", "使用履约资料", "批准调拨", "管理共享"],
                    "rows": [
                        ["查看者", "否", "获授权时可", "否", "否"],
                        ["编辑者", "是", "获授权时可", "否", "否"],
                        ["复核者", "评论", "获授权时可", "建议", "否"],
                        ["履约所有者", "是", "是", "是", "是"],
                    ],
                    "colWidths": [78, 74, 96, 76, 84],
                },
                "afterSections": [
                    ("版本审批", "本规范 1.0 版于 2026 年 12 月 2 日由供应链治理委员会批准。10 月草案曾允许库存低于安全线时自动跨区调拨,评审认为风险过高并删除该规则。已固定引用某一版本库存基线的图形在新版本出现后仍固定引用原版本。"),
                    ("库存阈值", "核心品类的安全库存水位为 30%,补货触发水位为 20%,缺货红线为 5%。单次波动只记录观察,连续两个盘点周期低于 20% 才触发补货审批。"),
                    ("降级触发", "当库存服务不可用时,允许基于已缓存数据继续绘图,但阻止生成新的调拨结论,并且不得把库存中断伪装成「有货可调」。"),
                ],
            },
        ],
    },
]

EXPANSION_FACTS += [
    # Microservice observability (validation)
    {"anchorId": "mso-id", "source": "expansion-observability", "version": "v1", "page": 1,
     "modality": "text", "goldMatch": "MSO-2026-33",
     "queries": ["微服务可观测性运行手册的编号是什么?"]},
    {"anchorId": "mso-dispatch-owner", "source": "expansion-observability", "version": "v1", "page": 2,
     "modality": "table", "goldMatch": "路由分发由值班组负责",
     "queries": ["路由分发由哪个团队负责?"]},
    {"anchorId": "mso-arch-route", "source": "expansion-observability", "version": "v1", "page": 3,
     "modality": "visual_flow", "goldMatch": "ALERT EVAL->ROUTE DISPATCH",
     "queries": ["架构图中告警评估之后是哪个组件?", "Which component follows ALERT EVAL in the route?"]},
    {"anchorId": "mso-arch-notify", "source": "expansion-observability", "version": "v1", "page": 3,
     "modality": "visual_flow", "goldMatch": "ROUTE DISPATCH->NOTIFY",
     "queries": ["架构图中路由分发之后是哪个环节?"]},
    {"anchorId": "mso-seq-diagnose", "source": "expansion-observability", "version": "v1", "page": 4,
     "modality": "visual_flow", "goldMatch": "ACK->DIAGNOSE",
     "queries": ["时序图中确认之后是什么?", "What follows ACK in the alert sequence?"]},
    {"anchorId": "mso-explicit-source", "source": "expansion-observability", "version": "v1", "page": 5,
     "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "只能在该范围内检索",
     "queries": ["用户点名某个告警规则后,检索范围应如何限制?", "How is retrieval scope limited after a specific alert rule is named?"]},
    {"anchorId": "mso-layout-retrieve", "source": "expansion-observability", "version": "v1", "page": 5,
     "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "属于纯图形操作,不需要重新检索",
     "queries": ["把已有采集链路图重新排布是否需要重新检索?"]},
    {"anchorId": "mso-version-pin", "source": "expansion-observability", "version": "v1", "page": 6,
     "modality": "table", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionConflict", "图册编辑者", "已有图形引用某告警基线版本,新版本已出现", "保持图形固定引用原版本"),
     "goldMatch": "仍固定引用原版本",
     "expectedAnswer": "保持固定在原版本;已引用原告警基线版本的图形不因新版本出现而改写。",
     "queries": ["已引用某告警基线版本的图形在新版本出现后如何处理?"]},
    {"anchorId": "mso-draft-reject", "source": "expansion-observability", "version": "v1", "page": 6,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("draftRejected", "可观测性治理委员会", "10 月草案允许错误率超阈值时自动重启实例", "使用批准版本,不自动重启"),
     "goldMatch": "评审认为风险过高并删除该规则",
     "expectedAnswer": "10 月草案的自动重启规则被否决。",
     "queries": ["10 月草案的自动重启规则是否被采纳?"]},
    {"anchorId": "mso-degrade", "source": "expansion-observability", "version": "v1", "page": 6,
     "modality": "text", "primaryCategory": "failure",
     "evaluationContext": _f("指标采集管道", "不可用", "基于已缓存数据继续绘图并阻止生成新告警结论", "把采集中断伪装成无告警"),
     "goldMatch": "不得把采集中断伪装成「无告警」",
     "expectedAnswer": "允许基于缓存继续绘图,阻止新告警结论,且不得伪装成无告警。",
     "queries": ["指标采集管道不可用时应如何处置?", "What is allowed and forbidden when the metric pipeline is down?"]},
    {"anchorId": "mso-slo", "source": "expansion-observability", "version": "v1", "page": 6,
     "modality": "text", "primaryCategory": "failure",
     "evaluationContext": _f("核心接口指标", "连续三个采样周期超过阈值", "触发人工确认", "凭单次抖动直接升级"),
     "goldMatch": "连续三个采样周期超过阈值才触发人工确认",
     "expectedAnswer": "连续三个采样周期超过阈值才触发人工确认,单次抖动只记录观察。",
     "queries": ["核心接口在什么条件下触发人工确认?"]},

    # Supply-chain fulfillment (holdout)
    {"anchorId": "scf-id", "source": "expansion-supply-chain", "version": "v1", "page": 1,
     "modality": "text", "goldMatch": "SCF-2026-28",
     "queries": ["供应链履约与库存调拨规范的编号是什么?"]},
    {"anchorId": "scf-orchestrate-owner", "source": "expansion-supply-chain", "version": "v1", "page": 2,
     "modality": "table", "goldMatch": "履约编排由履约组负责",
     "queries": ["履约编排由哪个团队负责?"]},
    {"anchorId": "scf-arch-route", "source": "expansion-supply-chain", "version": "v1", "page": 3,
     "modality": "visual_flow", "goldMatch": "STOCK CHECK->FULFILL ORCHESTRATE",
     "queries": ["架构图中库存检查之后是哪个组件?", "Which component follows STOCK CHECK in the route?"]},
    {"anchorId": "scf-arch-transfer", "source": "expansion-supply-chain", "version": "v1", "page": 3,
     "modality": "visual_flow", "goldMatch": "FULFILL ORCHESTRATE->TRANSFER",
     "queries": ["架构图中履约编排之后是哪个环节?"]},
    {"anchorId": "scf-seq-reconcile", "source": "expansion-supply-chain", "version": "v1", "page": 4,
     "modality": "visual_flow", "goldMatch": "ARRIVAL CHECK->RECONCILE",
     "queries": ["时序图中到货确认之后是什么?", "What follows ARRIVAL CHECK in the transfer sequence?"]},
    {"anchorId": "scf-explicit-source", "source": "expansion-supply-chain", "version": "v1", "page": 5,
     "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "只能在该范围内检索",
     "queries": ["用户点名某个调拨单后,检索范围应如何限制?"]},
    {"anchorId": "scf-clarify", "source": "expansion-supply-chain", "version": "v1", "page": 5,
     "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "先请求澄清",
     "queries": ["当「这个仓」可能指多个仓库时应先做什么?"]},
    {"anchorId": "scf-version-pin", "source": "expansion-supply-chain", "version": "v1", "page": 6,
     "modality": "table", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("versionConflict", "图册编辑者", "已有图形引用某库存基线版本,新版本已出现", "保持图形固定引用原版本"),
     "goldMatch": "仍固定引用原版本",
     "expectedAnswer": "保持固定在原版本;已引用原库存基线版本的图形不因新版本出现而改写。",
     "queries": ["已引用某库存基线版本的图形在新版本出现后如何处理?"]},
    {"anchorId": "scf-draft-reject", "source": "expansion-supply-chain", "version": "v1", "page": 6,
     "modality": "text", "primaryCategory": "versionAndAuthorization",
     "evaluationContext": _v("draftRejected", "供应链治理委员会", "10 月草案允许库存低于安全线时自动跨区调拨", "使用批准版本,不自动跨区调拨"),
     "goldMatch": "评审认为风险过高并删除该规则",
     "expectedAnswer": "10 月草案的自动跨区调拨规则被否决。",
     "queries": ["10 月草案的自动跨区调拨规则是否被采纳?"]},
    {"anchorId": "scf-degrade", "source": "expansion-supply-chain", "version": "v1", "page": 6,
     "modality": "text", "primaryCategory": "failure",
     "evaluationContext": _f("库存服务", "不可用", "基于已缓存数据继续绘图并阻止生成新调拨结论", "把库存中断伪装成有货可调"),
     "goldMatch": "不得把库存中断伪装成「有货可调」",
     "expectedAnswer": "允许基于缓存继续绘图,阻止新调拨结论,且不得伪装成有货可调。",
     "queries": ["库存服务不可用时应如何处置?"]},
    {"anchorId": "scf-threshold", "source": "expansion-supply-chain", "version": "v1", "page": 6,
     "modality": "text", "primaryCategory": "failure",
     "evaluationContext": _f("核心品类库存", "连续两个盘点周期低于 20%", "触发补货审批", "凭单次波动直接跨区调拨"),
     "goldMatch": "连续两个盘点周期低于 20% 才触发补货审批",
     "expectedAnswer": "连续两个盘点周期低于 20% 才触发补货审批,单次波动只记录观察。",
     "queries": ["核心品类在什么条件下触发补货审批?"]},
]

EXPANSION_MULTI_CASES += [
    {"category": "multi_evidence", "split": "validation",
     "sourceVersion": "expansion-observability:v1",
     "anchorIds": ["mso-arch-route", "mso-seq-diagnose"],
     "groupId": "route_and_sequence", "expectedPages": [3, 4],
     "expectedAnswer": "架构上 ALERT EVAL 之后进入 ROUTE DISPATCH;时序上 ACK 之后进入 DIAGNOSE。",
     "queries": [("zh", "请分别说明架构中告警评估之后、时序中确认之后各进入哪个环节?")]},
    {"category": "multi_evidence", "split": "holdout",
     "sourceVersion": "expansion-supply-chain:v1",
     "anchorIds": ["scf-arch-route", "scf-seq-reconcile"],
     "groupId": "route_and_sequence", "expectedPages": [3, 4],
     "expectedAnswer": "架构上 STOCK CHECK 之后进入 FULFILL ORCHESTRATE;时序上 ARRIVAL CHECK 之后进入 RECONCILE。",
     "queries": [("zh", "请分别说明架构中库存检查之后、时序中到货确认之后各进入哪个环节?")]},
]

EXPANSION_NO_ANSWER_CASES += [
    {"sourceVersion": "expansion-observability:v1", "query": "手册中告警平台管理员的登录口令是什么?"},
    {"sourceVersion": "expansion-observability:v1", "query": "What cloud monitoring API key is listed in the runbook?"},
    {"sourceVersion": "expansion-supply-chain:v1", "query": "规范中承运商的结算银行账号是什么?"},
    {"sourceVersion": "expansion-supply-chain:v1", "query": "规范中仓库门禁的密码是什么?"},
]


# ---- Batch 3: scanned documents to supply the OCR category ----
EXPANSION_SCANNED_DOCUMENTS = [
    {
        "source": "expansion-field-audit-scan",
        "documentFamily": "field-audit-scan",
        "version": "v1",
        "split": "development",
        "language": "mixed",
        "filename": "expansion-field-audit-scan-v1.pdf",
        "imagePrefix": "expansion-field-audit-scan",
        "pages": [
            {"title": "FIELD AUDIT SCAN", "subtitle": "FAS-2026-19 | image-only inspection record", "sections": [
                ("Scope", "The field audit covered line B between station 12 and station 19 on 14 October 2026. The report identifier is FAS-2026-19; FAS-2026-91 is a training form. Each finding was measured twice by different auditors."),
                ("Boundary", "This scanned record contains no worker phone numbers, gate codes or system passwords. It is only for OCR and retrieval evaluation."),
            ]},
            {"title": "1. VERIFIED FINDINGS", "subtitle": "measured values", "sections": [
                ("Reading", "At marker 16 the verified wear depth measured 27 mm. The nearby 34 mm value belongs to a calibration block and is not a real finding."),
                ("Threshold", "Manual engineering review is required when a verified depth reaches 25 mm or growth exceeds 5 mm between audits."),
                ("Action", "The 27 mm finding was entered into the engineering queue under an interim restriction; the record does not state the asset failed."),
            ]},
            {"title": "2. 中文复核记录", "subtitle": "扫描中文段落", "sections": [
                ("区段信息", "中文复核记录的区段编号为 CN-AUDIT-53,检查范围对应 B 线 16 号标记附近。培训页中的 CN-AUDIT-35 是演示编号,不能作为正式引用。"),
                ("处置条件", "当磨损深度达到 25 毫米或两次审计增长超过 5 毫米时进入工程复核。一次异常读数只保留观察记录。"),
                ("复查时间", "下一次现场复查安排在 2026 年 10 月 21 日 06:30,由资产完整性组负责。10 月 20 日是设备校准日期,不是现场复查时间。"),
            ]},
            {"title": "3. DEFECT REGISTER", "subtitle": "scanned table", "sections": [
                ("Table", "Location | Depth | Change | Status | Owner\nB-16 | 27 mm | +6 mm | ENGINEERING REVIEW | Integrity Team\nB-14 | 13 mm | +1 mm | MONITOR | Night Audit\nB-18 | 9 mm | NEW | VERIFY | Ultrasonic Team"),
                ("Reading note", "The status belongs to the same row as the location. B-16 is the only row assigned to ENGINEERING REVIEW."),
            ]},
            {"title": "4. ACTION AND LIMITS", "subtitle": "deadlines and negative evidence", "sections": [
                ("Deadline", "The engineering disposition is due by 18:00 on 18 October 2026. The planning meeting on 17 October is not the disposition deadline."),
                ("Rejected draft", "A draft note proposed a 30 mm review threshold. Engineering rejected it; the approved threshold remains 25 mm."),
                ("Unsupported", "No staff phone number, depot password or residential address is present. The correct response to those questions is no answer."),
            ]},
        ],
    },
    {
        "source": "expansion-approval-scan",
        "documentFamily": "approval-record-scan",
        "version": "v1",
        "split": "holdout",
        "language": "mixed",
        "filename": "expansion-approval-record-scan-v1.pdf",
        "imagePrefix": "expansion-approval-scan",
        "pages": [
            {"title": "扫描审批记录", "subtitle": "APR-2026-24 | 图像化审批单", "sections": [
                ("单据信息", "本审批记录的编号为 APR-2026-24,对应 11 月变更批次。培训样例 APR-2026-42 不能作为正式引用。"),
                ("边界", "本扫描件不含审批人私人电话、系统口令或银行账号,仅用于 OCR 与检索评估。"),
            ]},
            {"title": "1. APPROVAL VALUES", "subtitle": "scanned decision fields", "sections": [
                ("Decision", "The approved change window is 02:00 to 04:00 on 20 November 2026. The 03:00 to 05:00 window in an older draft is superseded."),
                ("Approver", "The change was approved by the Operations Council. A single reviewer comment does not constitute approval."),
                ("Limit", "Rollback begins after three consecutive health-check failures; a single failure raises an alert only."),
            ]},
            {"title": "2. 中文复核", "subtitle": "扫描中文段落", "sections": [
                ("复核编号", "中文复核栏的编号为 CN-APR-71,与英文审批单同属一次变更。CN-APR-17 是历史演练编号。"),
                ("复核条件", "变更实施前必须由运维负责人签字,登记人员可以核对批次号,但不能替代负责人批准。"),
                ("留存要求", "审批记录、健康检查回执与回滚日志至少留存 48 个月;导出的快照只能用于导航。"),
            ]},
            {"title": "3. NEGATIVE CONTROL", "subtitle": "scanned negative evidence", "sections": [
                ("Unsupported", "This approval record contains no administrator password, on-call phone number or vendor bank account. Those questions require a no-answer result."),
                ("Nearby", "A demo value 88% appears in a training note and is not an operational threshold."),
            ]},
        ],
    },
]

EXPANSION_SCAN_FACTS = [
    {"anchorId": "fas-id", "page": 1, "goldMatch": "FAS-2026-19",
     "queries": ["现场审计扫描件的编号是什么?", "What is the field audit scan identifier?"]},
    {"anchorId": "fas-depth", "page": 2, "goldMatch": "verified wear depth measured 27 mm",
     "queries": ["16 号标记处验证的磨损深度是多少?", "What verified wear depth was measured at marker 16?"]},
    {"anchorId": "fas-threshold", "page": 2, "goldMatch": "reaches 25 mm or growth exceeds 5 mm",
     "queries": ["达到多少毫米会进入工程复核?", "What depth triggers manual engineering review?"]},
    {"anchorId": "fas-cn-id", "page": 3, "goldMatch": "CN-AUDIT-53",
     "queries": ["中文复核记录的区段编号是什么?", "What section id appears in the Chinese audit scan?"]},
    {"anchorId": "fas-cn-recheck", "page": 3, "goldMatch": "2026 年 10 月 21 日 06:30",
     "queries": ["下一次现场复查安排在什么时候?"]},
    {"anchorId": "fas-row-status", "page": 4, "goldMatch": "B-16 | 27 mm | +6 mm | ENGINEERING REVIEW",
     "queries": ["扫描表格中 B-16 的状态是什么?", "What status does row B-16 have in the scan?"]},
    {"anchorId": "fas-deadline", "page": 5, "goldMatch": "18:00 on 18 October 2026",
     "queries": ["工程处置的截止时间是什么?", "When is the engineering disposition due?"]},
    {"anchorId": "fas-approved-threshold", "page": 5, "goldMatch": "approved threshold remains 25 mm",
     "queries": ["拒绝草案后保留的复核阈值是多少?"]},
    {"anchorId": "apr-id", "page": 1, "goldMatch": "APR-2026-24",
     "queries": ["扫描审批记录的编号是什么?", "What is the scanned approval record identifier?"]},
    {"anchorId": "apr-window", "page": 2, "goldMatch": "02:00 to 04:00 on 20 November 2026",
     "queries": ["批准的变更窗口是什么时候?", "What is the approved change window?"]},
    {"anchorId": "apr-approver", "page": 2, "goldMatch": "approved by the Operations Council",
     "queries": ["谁批准了这次变更?", "Who approved the change?"]},
    {"anchorId": "apr-rollback", "page": 2, "goldMatch": "three consecutive health-check failures",
     "queries": ["什么条件下开始回滚?", "What triggers rollback per the scan?"]},
    {"anchorId": "apr-cn-id", "page": 3, "goldMatch": "CN-APR-71",
     "queries": ["中文复核栏的编号是什么?"]},
    {"anchorId": "apr-retain", "page": 3, "goldMatch": "至少留存 48 个月",
     "queries": ["审批记录至少留存多久?"]},
]
for _s in EXPANSION_SCAN_FACTS:
    _s.setdefault("source", None)
_FAS = {"fas-id","fas-depth","fas-threshold","fas-cn-id","fas-cn-recheck","fas-row-status","fas-deadline","fas-approved-threshold"}
for _s in EXPANSION_SCAN_FACTS:
    _s["source"] = "expansion-field-audit-scan" if _s["anchorId"] in _FAS else "expansion-approval-scan"
    _s["version"] = "v1"; _s["modality"] = "ocr"; _s["primaryCategory"] = "ocr"

EXPANSION_NO_ANSWER_CASES += [
    {"sourceVersion": "expansion-field-audit-scan:v1", "query": "扫描件中现场人员的电话号码是多少?"},
    {"sourceVersion": "expansion-approval-scan:v1", "query": "审批记录中的管理员口令是什么?"},
]


# ---- Batch 4: rule-dense docs for failure / retrievalDecision / versionAndAuthorization ----
EXPANSION_DIGITAL_DOCUMENTS += [
    {
        "source": "expansion-platform-resilience",
        "documentFamily": "platform-resilience",
        "version": "v1", "split": "development", "language": "mixed",
        "filename": "expansion-platform-resilience-v1.pdf", "template": "paper",
        "footerLabel": "Platform Resilience", "bodyFontSize": 9.8, "bodyLeading": 13.8,
        "pages": [
            {"title": "平台弹性与降级处置规范", "subtitle": "PRE-2026-51 | degraded-mode and recovery",
             "layout": "columns", "sections": [
                ("适用范围", "本规范定义平台依赖故障时的降级与恢复行为,供图形化评审使用。编号为 PRE-2026-51;PRE-2026-15 是压测脚本。"),
                ("Scope", "It covers vector search, object storage, OCR, visual verification and canvas save. It does not cover physical power or network hardware."),
                ("阅读约定", "「降级」指能力受限但仍安全可用,「中断」指能力完全不可用。带编号的处置规则对应唯一事实。"),
                ("拒答边界", "本规范不含堡垒机口令、值班电话或云密钥,相关问题必须拒答。"),
             ]},
            {"title": "1. 故障处置矩阵", "subtitle": "dependency, state, allowed action", "sections": [
                ("Matrix rule", "The allowed action is conservative; a capability stays available only when it does not depend on the failed path."),
             ], "table": {"headers": ["Failure", "State", "Allowed", "Blocked", "Severity"], "rows": [
                ["Vector search", "retrieval degraded", "manual drawing", "material search", "SEV-2"],
                ["Object store", "hydration failed", "edit saved XML", "open evidence", "SEV-2"],
                ["OCR", "scan unavailable", "native PDF", "scan claims", "SEV-3"],
                ["Visual verifier", "visual pending", "text claims", "pixel relations", "SEV-3"],
                ["Canvas save", "save blocked", "download draft", "publish", "SEV-1"],
             ], "colWidths": [92, 100, 92, 84, 60]},
             "afterSections": [("Matrix result", "During a vector-search failure, manual drawing remains available but material search is blocked. A canvas-save failure is SEV-1 because publishing could lose user work.")]},
            {"title": "2. 恢复验证规则", "subtitle": "probe order and gates", "sections": [
                ("探测顺序", "先验证租户与图册过滤,再验证版本选择,再验证证据水合,最后验证生成。一次不带过滤的成功查询不是有效恢复探测。"),
                ("Search recovery", "Vector search returns to normal only after 12 consecutive scoped probes succeed across three test tenants. The nearby 21 is a capacity drill, not the recovery threshold."),
                ("OCR recovery", "OCR recovery requires anchor recall of at least 0.95 on the pinned scan suite and no cross-page substitution. Character error rate alone cannot prove row correctness."),
                ("No-evidence rule", "If retrieval did not execute, the system must report that search could not run and must not claim that no evidence exists."),
             ]},
            {"title": "3. 检索决策", "subtitle": "when to retrieve and source order", "layout": "columns", "sections": [
                ("是否检索", "把已有故障处置图重新排布属于纯图形操作,不需要重新检索。新增故障事实或比较阈值时必须检索并验证证据。"),
                ("显式来源", "用户点名某个处置规则资料时,只能在该范围内检索;若缺少目标事实,应说明证据不足,而不是改用最新资料补全。"),
                ("来源顺序", "没有显式来源时,先搜索当前图册允许共享的资料,再在开启自动检索后扩大到更广授权资料。"),
                ("澄清条件", "当「这个故障」可能对应多个依赖时,先请求澄清,不能用相似度最高的结果替代用户选择。"),
             ]},
            {"title": "4. 版本审批", "subtitle": "pin, default and rejected draft", "sections": [
                ("版本审批", "本规范 1.0 版于 2026 年 12 月 4 日由可靠性委员会批准。10 月草案允许 5 次探测即恢复,评审认为风险过高并删除该规则,改为 12 次连续探测。已固定引用某版本处置基线的图形在新版本出现后仍固定引用原版本。"),
                ("Severity note", "SEV-1 means possible cross-scope disclosure or corrupted saved canvas; SEV-2 means a material task cannot complete without data loss; SEV-3 means a retryable delay with preserved work."),
             ]},
        ],
    },
    {
        "source": "expansion-material-governance",
        "documentFamily": "material-governance",
        "version": "v1", "split": "validation", "language": "mixed",
        "filename": "expansion-material-governance-v1.pdf", "template": "paper",
        "footerLabel": "Material Governance", "bodyFontSize": 9.8, "bodyLeading": 13.8,
        "pages": [
            {"title": "资料治理与检索控制规范", "subtitle": "MGV-2026-37 | scope, retrieval and version control",
             "layout": "columns", "sections": [
                ("适用范围", "本规范定义资料范围、检索决策与版本控制,供图形化评审使用。编号为 MGV-2026-37;MGV-2026-73 是培训样例。"),
                ("Scope", "It governs when to retrieve, which source scope applies and how versions are pinned. It does not cover billing or storage hardware."),
                ("阅读约定", "「显式来源」指用户点名的资料,「自动检索」指无显式来源时的授权范围搜索。带编号的规则对应唯一事实。"),
                ("拒答边界", "本规范不含成员邮箱、邀请令牌或系统口令,相关问题必须拒答。"),
             ]},
            {"title": "1. 检索决策规则", "subtitle": "when and where to retrieve", "layout": "columns", "sections": [
                ("是否检索", "纯布局操作不需要检索;新增事实节点、比较数值或解释关系时必须检索并验证证据。"),
                ("显式来源", "用户点名某份资料时,只能在该范围内检索;指定版本缺少目标事实时应说明证据不足,而不是自动改用最新版。"),
                ("图册优先", "没有显式来源时,先搜索当前图册允许共享的资料,再在开启自动检索后扩大到更广授权资料。"),
                ("Broader search", "Broader allowed material is considered only when automatic selection is enabled, and the response records that scope expansion."),
                ("Zero result", "A no-result response is valid only after every allowed search path completed; an unavailable index produces a degraded state, not a no-answer."),
                ("澄清条件", "指代不明时先请求澄清,不能用最高相似度结果替代用户选择。"),
             ]},
            {"title": "2. 授权范围", "subtitle": "acting user and chartbook narrowing", "layout": "columns", "sections": [
                ("Acting user", "Every request is evaluated as the acting user; material never shared with them stays inaccessible even when a cached citation is visible."),
                ("图册收窄", "在图册内绘图只能使用该图册挂载的资料,不得漏用用户库中其他未挂载的资料。图册是收窄范围,不是扩大。"),
                ("Removed share", "When a share is removed, new retrieval cannot use that material for the acting user; an existing canvas keeps only its visible citation label."),
                ("跨用户", "系统永远按当前用户评估,任何请求都不能检索到其他用户上传的资料。"),
             ]},
            {"title": "3. 版本控制", "subtitle": "pin, default, conflict", "sections": [
                ("版本固定", "已引用某版本资料的图形在重新打开时仍固定引用该版本;新的请求默认使用最新就绪版本,除非用户显式选择旧版本。"),
                ("版本审批", "本规范 1.0 版于 2026 年 11 月 26 日由资料治理委员会批准。10 月草案允许布局操作时自动引用最新版本,评审认为风险过高并删除该规则。"),
                ("缺失版本", "被固定到不存在版本的请求必须报告版本不可用,而不是静默改用最新版本。"),
                ("冲突处理", "当同一资料存在多个版本且数值冲突时,回答必须锁定被引用的版本,不能把新版本数值套用到已固定旧版本的图形。"),
             ]},
        ],
    },
]

EXPANSION_FACTS += [
    # platform-resilience (dev) — failure dense
    {"anchorId": "pre-id", "source": "expansion-platform-resilience", "version": "v1", "page": 1, "modality": "text", "goldMatch": "PRE-2026-51", "queries": ["平台弹性与降级处置规范的编号是什么?"]},
    {"anchorId": "pre-vector", "source": "expansion-platform-resilience", "version": "v1", "page": 2, "modality": "table", "primaryCategory": "failure", "evaluationContext": _f("vector search", "retrieval degraded", "keep manual drawing and block material search", "claim no evidence exists"), "goldMatch": "manual drawing remains available but material search is blocked", "expectedAnswer": "Manual drawing stays; material search is blocked.", "queries": ["What is allowed and blocked during a vector-search failure?", "向量检索故障时允许和禁止什么?"]},
    {"anchorId": "pre-objstore", "source": "expansion-platform-resilience", "version": "v1", "page": 2, "modality": "table", "primaryCategory": "failure", "evaluationContext": _f("object store", "hydration failed", "edit saved XML, block open evidence", "open evidence as if hydration succeeded"), "goldMatch": "Object store", "expectedAnswer": "Editing saved XML stays; opening evidence is blocked.", "queries": ["对象存储故障时可以做什么?", "What is allowed when object storage hydration fails?"]},
    {"anchorId": "pre-canvas", "source": "expansion-platform-resilience", "version": "v1", "page": 2, "modality": "table", "primaryCategory": "failure", "evaluationContext": _f("canvas save", "save blocked", "download draft, disable publish", "publish while save is blocked"), "goldMatch": "canvas-save failure is SEV-1", "expectedAnswer": "SEV-1; download draft stays, publish disabled.", "queries": ["画布保存故障是什么严重等级?", "What severity is a canvas-save failure?"]},
    {"anchorId": "pre-probes", "source": "expansion-platform-resilience", "version": "v1", "page": 3, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("vector search", "recovering", "stay degraded until 12 consecutive scoped probes succeed", "restore early"), "goldMatch": "12 consecutive scoped probes", "expectedAnswer": "12 consecutive scoped probes across three test tenants.", "queries": ["搜索恢复需要多少次连续探测?", "How many scoped probes are required for search recovery?"]},
    {"anchorId": "pre-ocr-gate", "source": "expansion-platform-resilience", "version": "v1", "page": 3, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("OCR", "recovering", "gate OCR recovery until anchor recall reaches 0.95", "mark OCR healthy below the gate"), "goldMatch": "anchor recall of at least 0.95", "expectedAnswer": "Anchor recall >= 0.95 on the pinned scan suite.", "queries": ["OCR 恢复要求的 anchor recall 是多少?", "What anchor recall is required for OCR recovery?"]},
    {"anchorId": "pre-no-evidence", "source": "expansion-platform-resilience", "version": "v1", "page": 3, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("material retrieval", "did not execute", "report that search could not run", "claim no evidence exists"), "goldMatch": "must not claim that no evidence exists", "expectedAnswer": "Report retrieval did not run; do not claim no evidence exists.", "queries": ["检索未执行时能否声称没有证据?", "May the system say no evidence exists when retrieval did not run?"]},
    {"anchorId": "pre-explicit", "source": "expansion-platform-resilience", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "只能在该范围内检索", "queries": ["点名某个处置规则资料后检索范围如何限制?", "How is scope limited after a disposition rule is named?"]},
    {"anchorId": "pre-layout", "source": "expansion-platform-resilience", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "属于纯图形操作,不需要重新检索", "queries": ["把已有故障处置图重新排布是否需要检索?"]},
    {"anchorId": "pre-clarify", "source": "expansion-platform-resilience", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "先请求澄清", "queries": ["当「这个故障」可能对应多个依赖时先做什么?"]},
    {"anchorId": "pre-version", "source": "expansion-platform-resilience", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("draftRejected", "可靠性委员会", "10 月草案允许 5 次探测即恢复", "使用批准版本的 12 次连续探测"), "goldMatch": "评审认为风险过高并删除该规则", "expectedAnswer": "5 次探测草案被否决,改为 12 次连续探测。", "queries": ["10 月草案的 5 次探测恢复规则是否被采纳?"]},
    {"anchorId": "pre-version-pin", "source": "expansion-platform-resilience", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionConflict", "图册编辑者", "已有图形引用某处置基线版本,新版本已出现", "保持图形固定引用原版本"), "goldMatch": "仍固定引用原版本", "expectedAnswer": "保持固定在原版本。", "queries": ["已引用某处置基线版本的图形在新版本出现后如何处理?"]},

    # material-governance (val) — retrieval / version / cross dense
    {"anchorId": "mgv-id", "source": "expansion-material-governance", "version": "v1", "page": 1, "modality": "text", "goldMatch": "MGV-2026-37", "queries": ["资料治理与检索控制规范的编号是什么?", "What is the material governance identifier?"]},
    {"anchorId": "mgv-explicit", "source": "expansion-material-governance", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "只能在该范围内检索", "queries": ["用户点名某份资料后检索范围如何限制?", "How is scope limited after a user names material?"]},
    {"anchorId": "mgv-chartbook", "source": "expansion-material-governance", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "先搜索当前图册允许共享的资料", "queries": ["没有显式来源时先搜索什么?"]},
    {"anchorId": "mgv-broader", "source": "expansion-material-governance", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "considered only when automatic selection is enabled", "queries": ["When is broader allowed material considered?", "更广授权资料在什么条件下才被检索?"]},
    {"anchorId": "mgv-zero", "source": "expansion-material-governance", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "an unavailable index produces a degraded state, not a no-answer", "queries": ["索引不可用时应产生什么状态?"]},
    {"anchorId": "mgv-chartbook-narrow", "source": "expansion-material-governance", "version": "v1", "page": 3, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "图册编辑者", "图册内绘图访问用户库中未挂载资料", "只能使用图册挂载的资料"), "goldMatch": "只能使用该图册挂载的资料", "expectedAnswer": "只能用图册挂载的资料;图册是收窄不是扩大。", "queries": ["在图册内绘图能否使用用户库中未挂载的资料?", "In a chartbook, may drawing use unmounted material from the user's library?"]},
    {"anchorId": "mgv-cross-user", "source": "expansion-material-governance", "version": "v1", "page": 3, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "acting user", "attempt to read another user's uploaded material", "deny; always evaluated as the acting user"), "goldMatch": "任何请求都不能检索到其他用户上传的资料", "expectedAnswer": "不能;永远按当前用户评估,不跨用户。", "queries": ["一个用户能否检索到其他用户上传的资料?"]},
    {"anchorId": "mgv-removed", "source": "expansion-material-governance", "version": "v1", "page": 3, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "former collaborator", "share was removed", "block new retrieval of that material"), "goldMatch": "new retrieval cannot use that material", "expectedAnswer": "共享移除后新检索不能再用该资料。", "queries": ["What happens to retrieval after a share is removed?", "共享被移除后检索如何处理?"]},
    {"anchorId": "mgv-version-pin", "source": "expansion-material-governance", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionConflict", "图册编辑者", "图形已引用某版本,新版本已就绪", "保持固定引用原版本"), "goldMatch": "仍固定引用该版本", "expectedAnswer": "保持固定;新请求才默认最新版本。", "queries": ["已引用某版本的图形重新打开时用哪个版本?"]},
    {"anchorId": "mgv-missing", "source": "expansion-material-governance", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionMissing", "agent", "请求被固定到不存在的版本", "报告版本不可用"), "goldMatch": "必须报告版本不可用", "expectedAnswer": "报告版本不可用,不静默改用最新版本。", "queries": ["被固定到不存在版本的请求应如何处理?"]},
    {"anchorId": "mgv-draft", "source": "expansion-material-governance", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("draftRejected", "资料治理委员会", "10 月草案允许布局时自动引用最新版本", "使用批准版本,布局不自动改引用"), "goldMatch": "评审认为风险过高并删除该规则", "expectedAnswer": "自动引用最新版本的草案被否决。", "queries": ["10 月草案的自动引用最新版本规则是否被采纳?"]},
]


# ---- Batch 5: top-up facts + multi/noAnswer/cross cases to clear every category >= 50 ----
EXPANSION_FACTS += [
    # extra failure (platform-resilience matrix rows + recovery + severity)
    {"anchorId": "pre-ocr-row", "source": "expansion-platform-resilience", "version": "v1", "page": 2, "modality": "table", "primaryCategory": "failure", "evaluationContext": _f("OCR", "scan unavailable", "keep native PDF answers and block scan claims", "answer scan claims while OCR is down"), "goldMatch": "scan unavailable", "expectedAnswer": "Native PDF stays; scan claims blocked.", "queries": ["OCR 不可用时允许什么?", "What is allowed when OCR is unavailable?"]},
    {"anchorId": "pre-visual-row", "source": "expansion-platform-resilience", "version": "v1", "page": 2, "modality": "table", "primaryCategory": "failure", "evaluationContext": _f("visual verifier", "visual pending", "keep text claims and block pixel relations", "confirm pixel relations while pending"), "goldMatch": "visual pending", "expectedAnswer": "Text claims stay; pixel relations blocked.", "queries": ["视觉验证待定时允许什么?", "What is allowed when visual verification is pending?"]},
    {"anchorId": "pre-probe-order", "source": "expansion-platform-resilience", "version": "v1", "page": 3, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("recovery probe", "verifying", "verify tenant and chartbook filters first", "treat an unfiltered query as a valid probe"), "goldMatch": "先验证租户与图册过滤", "expectedAnswer": "先验证租户与图册过滤,不带过滤的查询不算有效探测。", "queries": ["恢复探测应先验证什么?", "What is verified first during recovery probing?"]},
    {"anchorId": "pre-severity", "source": "expansion-platform-resilience", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("saved canvas", "possible corruption", "classify as SEV-1", "treat cross-scope disclosure as low severity"), "goldMatch": "SEV-1 means possible cross-scope disclosure", "expectedAnswer": "SEV-1 = 可能跨域泄漏或画布损坏。", "queries": ["SEV-1 代表什么?", "What does SEV-1 mean?"]},
    # extra retrieval (governance zero-result + resilience source order)
    {"anchorId": "mgv-layout", "source": "expansion-material-governance", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "纯布局操作不需要检索", "queries": ["纯布局操作是否需要检索?"]},
    {"anchorId": "pre-source-order", "source": "expansion-platform-resilience", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "先搜索当前图册允许共享的资料", "queries": ["没有显式来源时先搜索什么?", "Without an explicit source, what is searched first?"]},
    # extra version (governance conflict + resilience)
    {"anchorId": "mgv-conflict", "source": "expansion-material-governance", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionConflict", "reader", "同一资料多版本数值冲突", "锁定被引用版本,不套用新版本数值"), "goldMatch": "不能把新版本数值套用到已固定旧版本的图形", "expectedAnswer": "锁定被引用版本,不把新版本数值套用到旧版图形。", "queries": ["多版本数值冲突时如何回答?"]},
    {"anchorId": "dcc-event-degrade", "source": "expansion-datacenter-change", "version": "v1", "page": 2, "modality": "table", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("authorizationScope", "发布组", "尝试覆盖事件响应的降级结论", "拒绝;降级结论不能被发布环节覆盖"), "goldMatch": "降级结论不能被发布环节覆盖", "expectedAnswer": "不能;事件响应的降级结论不被发布覆盖。", "queries": ["发布环节能否覆盖事件响应的降级结论?"]},
    # cross-language candidates: English single queries on zh documents
    {"anchorId": "dcc-water-cross", "source": "expansion-datacenter-change", "version": "v1", "page": 6, "modality": "text", "goldMatch": "告警水位为 75%", "queries": ["What is the alert water level for the core resource pool?"]},
    {"anchorId": "dcc-approve-cross", "source": "expansion-datacenter-change", "version": "v1", "page": 6, "modality": "text", "goldMatch": "2026 年 11 月 28 日", "queries": ["When was the datacenter standard version 1.0 approved?"]},
    {"anchorId": "scf-stock-cross", "source": "expansion-supply-chain", "version": "v1", "page": 6, "modality": "text", "goldMatch": "安全库存水位为 30%", "queries": ["What is the safety-stock water level for core categories?"]},
    {"anchorId": "scf-approve-cross", "source": "expansion-supply-chain", "version": "v1", "page": 6, "modality": "text", "goldMatch": "2026 年 12 月 2 日", "queries": ["When was the supply-chain standard version 1.0 approved?"]},
    {"anchorId": "dcc-budget-cross", "source": "expansion-datacenter-change", "version": "v1", "page": 5, "modality": "text", "goldMatch": "基线检索 40 个证据候选", "queries": ["How many evidence candidates does the baseline retrieve per the datacenter standard?"]},
    {"anchorId": "scf-redline-cross", "source": "expansion-supply-chain", "version": "v1", "page": 6, "modality": "text", "goldMatch": "缺货红线为 5%", "queries": ["What is the stock-out red line for core categories?"]},
    {"anchorId": "pre-cn-approve-cross", "source": "expansion-platform-resilience", "version": "v1", "page": 5, "modality": "text", "goldMatch": "2026 年 12 月 4 日", "queries": ["When was the platform resilience standard version 1.0 approved?"]},
    {"anchorId": "mso-cn-approve-cross", "source": "expansion-observability", "version": "v1", "page": 6, "modality": "text", "goldMatch": "2026 年 11 月 22 日", "queries": ["When was the observability runbook version 1.0 approved?"]},
]

EXPANSION_MULTI_CASES += [
    {"category": "multi_evidence", "split": "development", "sourceVersion": "expansion-datacenter-change:v1", "anchorIds": ["dcc-id", "dcc-threshold"], "groupId": "id_and_threshold", "expectedPages": [1, 6], "expectedAnswer": "编号 DCC-2026-40;连续三个采样周期超过 85% 才进入人工复核。", "queries": [("zh", "数据中心规范的编号是什么,核心资源池在什么条件下进入人工复核?")]},
    {"category": "multi_evidence", "split": "development", "sourceVersion": "expansion-platform-resilience:v1", "anchorIds": ["pre-vector", "pre-canvas"], "groupId": "failure_actions", "expectedPages": [2, 2], "expectedAnswer": "向量故障时手动绘图可用、资料检索被阻止;画布保存故障为 SEV-1。", "queries": [("zh", "向量检索故障和画布保存故障分别如何处置?")]},
    {"category": "multi_evidence", "split": "development", "sourceVersion": "expansion-platform-resilience:v1", "anchorIds": ["pre-probes", "pre-ocr-gate"], "groupId": "recovery_gates", "expectedPages": [3, 3], "expectedAnswer": "搜索恢复需 12 次连续探测;OCR 恢复需 anchor recall 不低于 0.95。", "queries": [("en", "What are the search-recovery probe count and the OCR recovery recall gate?")]},
    {"category": "multi_evidence", "split": "validation", "sourceVersion": "expansion-material-governance:v1", "anchorIds": ["mgv-chartbook-narrow", "mgv-cross-user"], "groupId": "scope_invariants", "expectedPages": [3, 3], "expectedAnswer": "图册内只能用挂载资料;任何请求都不能检索其他用户上传的资料。", "queries": [("zh", "图册收窄和跨用户隔离两条规则分别是什么?")]},
    {"category": "multi_evidence", "split": "validation", "sourceVersion": "expansion-material-governance:v1", "anchorIds": ["mgv-version-pin", "mgv-missing"], "groupId": "version_rules", "expectedPages": [4, 4], "expectedAnswer": "已引用版本固定;被固定到不存在版本必须报告不可用。", "queries": [("zh", "版本固定和缺失版本两条规则分别是什么?")]},
    {"category": "multi_evidence", "split": "validation", "sourceVersion": "expansion-observability:v1", "anchorIds": ["mso-id", "mso-slo"], "groupId": "id_and_slo", "expectedPages": [1, 6], "expectedAnswer": "编号 MSO-2026-33;连续三个采样周期超过阈值才触发人工确认。", "queries": [("zh", "可观测性手册编号是什么,核心接口在什么条件下触发人工确认?")]},
    {"category": "multi_evidence", "split": "holdout", "sourceVersion": "expansion-supply-chain:v1", "anchorIds": ["scf-id", "scf-threshold"], "groupId": "id_and_threshold", "expectedPages": [1, 6], "expectedAnswer": "编号 SCF-2026-28;连续两个盘点周期低于 20% 才触发补货审批。", "queries": [("zh", "供应链规范编号是什么,核心品类在什么条件下触发补货审批?")]},
    {"category": "multi_evidence", "split": "development", "sourceVersion": "expansion-field-audit-scan:v1", "anchorIds": ["fas-depth", "fas-threshold"], "groupId": "depth_and_threshold", "expectedPages": [2, 2], "expectedAnswer": "16 号标记验证磨损深度 27 mm;达到 25 mm 或增长超过 5 mm 进入工程复核。", "queries": [("zh", "16 号标记的磨损深度是多少,达到什么条件进入工程复核?")]},
]

EXPANSION_NO_ANSWER_CASES += [
    {"sourceVersion": "expansion-platform-resilience:v1", "query": "规范中堡垒机的登录口令是什么?"},
    {"sourceVersion": "expansion-platform-resilience:v1", "query": "What on-call phone number is in the resilience standard?"},
    {"sourceVersion": "expansion-material-governance:v1", "query": "规范中某成员的邀请令牌是什么?"},
    {"sourceVersion": "expansion-material-governance:v1", "query": "What member email is listed in the governance standard?"},
    {"sourceVersion": "expansion-datacenter-change:v1", "query": "What bastion host password is in the datacenter standard?"},
    {"sourceVersion": "expansion-observability:v1", "query": "规范中数据库连接串是什么?"},
    {"sourceVersion": "expansion-supply-chain:v1", "query": "What vendor bank account appears in the supply-chain standard?"},
    {"sourceVersion": "expansion-field-audit-scan:v1", "query": "扫描件中的仓库门禁密码是什么?"},
    {"sourceVersion": "expansion-approval-scan:v1", "query": "What administrator password is in the approval scan?"},
    {"sourceVersion": "expansion-datacenter-change:v1", "query": "规范中值班人员的私人电话是多少?"},
    {"sourceVersion": "expansion-observability:v1", "query": "What cloud monitoring token appears in the observability runbook?"},
    {"sourceVersion": "expansion-supply-chain:v1", "query": "规范中客户的身份证号是什么?"},
]


# ---- Batch 6: final top-up (dev candidates + remaining category gaps) ----
EXPANSION_FACTS += [
    # retrieval (new gold, double query -> dev boost)
    {"anchorId": "daa-recall-budget", "source": "drawio-agent-architecture", "version": "v1", "page": 4, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "Candidate count is a recall budget", "queries": ["What does candidate count represent in retrieval?", "候选数量代表什么?"]},
    {"anchorId": "dcc-metadata", "source": "expansion-datacenter-change", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "范围变化写入回答元数据", "queries": ["检索范围扩大后必须记录到哪里?", "Where must a scope expansion be recorded?"]},
    {"anchorId": "pss-metadata", "source": "scenario-payment-settlement", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "范围扩大必须写入回答元数据", "queries": ["支付说明书要求检索范围扩大后记录到哪里?"]},
    {"anchorId": "pre-degraded-retrieval", "source": "expansion-platform-resilience", "version": "v1", "page": 3, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "一次不带过滤的成功查询不是有效恢复探测", "queries": ["不带过滤的成功查询算有效恢复探测吗?", "Is an unfiltered successful query a valid recovery probe?"]},
    # version (new gold)
    {"anchorId": "daa-latest", "source": "drawio-agent-architecture", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionDefault", "agent", "new request without explicit version", "use the latest ready version"), "goldMatch": "defaults to the latest ready version", "expectedAnswer": "New diagrams use the latest ready version unless the user selects another.", "queries": ["What version do new diagrams use by default?", "新图默认使用哪个版本?"]},
    {"anchorId": "pss-default", "source": "scenario-payment-settlement", "version": "v1", "page": 6, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionDefault", "agent", "新请求未指定版本", "默认使用最新就绪版本"), "goldMatch": "新的请求默认使用最新就绪版本", "expectedAnswer": "新请求默认使用最新就绪版本。", "queries": ["支付说明书中新请求默认使用哪个版本?"]},
    {"anchorId": "dcc-latest", "source": "expansion-datacenter-change", "version": "v1", "page": 6, "modality": "table", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("versionConflict", "图册编辑者", "已有图形引用原容量基线版本", "保持固定引用原版本"), "goldMatch": "仍固定引用原版本", "expectedAnswer": "保持固定在原版本。", "queries": ["数据中心规范中已引用原容量基线版本的图形如何处理?"]},
    # failure (severity definitions)
    {"anchorId": "pre-sev2", "source": "expansion-platform-resilience", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("material task", "cannot complete", "classify as SEV-2", "lose data silently"), "goldMatch": "SEV-2 means a material task cannot complete without data loss", "expectedAnswer": "SEV-2 = 资料任务无法在不丢数据下完成。", "queries": ["SEV-2 代表什么?", "What does SEV-2 mean?"]},
    {"anchorId": "pre-sev3", "source": "expansion-platform-resilience", "version": "v1", "page": 5, "modality": "text", "primaryCategory": "failure", "evaluationContext": _f("dependency op", "retryable delay", "classify as SEV-3 with preserved work", "discard preserved work"), "goldMatch": "SEV-3 means a retryable delay with preserved work", "expectedAnswer": "SEV-3 = 可重试延迟且工作保留。", "queries": ["SEV-3 代表什么?", "What does SEV-3 mean?"]},
    # cross-language top-up (en query on zh docs)
    {"anchorId": "dcc-review-cross", "source": "expansion-datacenter-change", "version": "v1", "page": 6, "modality": "text", "goldMatch": "人工复核水位为 85%", "queries": ["What is the manual-review water level in the datacenter standard?"]},
    {"anchorId": "scf-restock-cross", "source": "expansion-supply-chain", "version": "v1", "page": 6, "modality": "text", "goldMatch": "补货触发水位为 20%", "queries": ["What is the restock trigger water level in the supply-chain standard?"]},
]

EXPANSION_MULTI_CASES += [
    {"category": "multi_evidence", "split": "development", "sourceVersion": "expansion-platform-resilience:v1", "anchorIds": ["pre-probes", "pre-no-evidence"], "groupId": "recovery_and_honesty", "expectedPages": [3, 3], "expectedAnswer": "搜索恢复需 12 次连续探测;检索未执行时须报告而非声称没有证据。", "queries": [("zh", "搜索恢复需要多少次探测,检索未执行时应如何回答?")]},
    {"category": "multi_evidence", "split": "development", "sourceVersion": "expansion-datacenter-change:v1", "anchorIds": ["dcc-orchestrate-owner", "dcc-degrade"], "groupId": "owner_and_degrade", "expectedPages": [2, 6], "expectedAnswer": "发布编排由发布组负责;对象存储不可用时可编辑缓存画布但阻止加载新证据。", "queries": [("zh", "发布编排谁负责,对象存储不可用时如何处置?")]},
    {"category": "multi_evidence", "split": "development", "sourceVersion": "drawio-agent-architecture:v1", "anchorIds": ["daa-candidates", "daa-recall-budget"], "groupId": "candidates_and_budget", "expectedPages": [4, 4], "expectedAnswer": "基线检索 40 个候选;候选数量是召回预算而非展示给用户的形状数。", "queries": [("en", "How many candidates does the baseline retrieve and what does that count represent?")]},
    {"category": "multi_evidence", "split": "development", "sourceVersion": "scenario-payment-settlement:v1", "anchorIds": ["pss-version-pin", "pss-default"], "groupId": "pin_and_default", "expectedPages": [6, 6], "expectedAnswer": "已引用 V1 的图形固定 V1;新请求默认使用最新就绪版本。", "queries": [("zh", "已引用 V1 的图形和新请求分别使用哪个版本?")]},
    {"category": "multi_evidence", "split": "development", "sourceVersion": "expansion-platform-resilience:v1", "anchorIds": ["pre-sev2", "pre-sev3"], "groupId": "severity_defs", "expectedPages": [5, 5], "expectedAnswer": "SEV-2 资料任务无法无损完成;SEV-3 可重试延迟且工作保留。", "queries": [("en", "What do SEV-2 and SEV-3 mean?")]},
    {"category": "multi_evidence", "split": "development", "sourceVersion": "expansion-datacenter-change:v1", "anchorIds": ["dcc-arch-apply", "dcc-arch-route"], "groupId": "route_steps", "expectedPages": [3, 3], "expectedAnswer": "容量检查后进入变更编排,变更编排后进入落地。", "queries": [("zh", "架构图中容量检查与变更编排之后分别进入哪个环节?")]},
]

EXPANSION_NO_ANSWER_CASES += [
    {"sourceVersion": "expansion-platform-resilience:v1", "query": "规范中云账号的密钥是什么?"},
    {"sourceVersion": "expansion-datacenter-change:v1", "query": "What jump-host credential is stored in the datacenter standard?"},
]


EXPANSION_FACTS += [
    {"anchorId": "mgv-clarify", "source": "expansion-material-governance", "version": "v1", "page": 2, "modality": "text", "primaryCategory": "retrievalDecision", "goldMatch": "指代不明时先请求澄清", "queries": ["指代不明时应先做什么?", "What should happen when a reference is ambiguous?"]},
    {"anchorId": "dwh-draft", "source": "drawio-workflow-handbook", "version": "v1", "page": 6, "modality": "text", "primaryCategory": "versionAndAuthorization", "evaluationContext": _v("draftRejected", "Diagram Experience Lead", "11 月草案允许布局操作时自动补充事实", "使用批准版本,布局不自动补事实"), "goldMatch": "评审认为风险过高并删除该规则", "expectedAnswer": "自动补充事实的草案被否决。", "queries": ["制图手册 11 月草案的自动补事实规则是否被采纳?", "Was the November auto-fill draft adopted in the workflow handbook?"]},
]
