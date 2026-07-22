"""Authored multi-source chartbook cases for E4 source-diversity evaluation."""

from __future__ import annotations


E4_CHARTBOOKS = [
    {
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "split": "development",
        "mountedSourceVersions": [
            "drawio-agent-architecture:v1",
            "drawio-workflow-handbook:v1",
            "expansion-platform-resilience:v1",
            "expansion-datacenter-change:v1",
        ],
        "unmountedSourceVersions": [
            "scenario-payment-settlement:v1",
            "realistic-harbor-report:v1",
        ],
    },
    {
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "split": "validation",
        "mountedSourceVersions": [
            "drawio-collaboration-governance:v1",
            "expansion-material-governance:v1",
            "expansion-observability:v1",
            "scenario-ota-rollout:v1",
        ],
        "unmountedSourceVersions": [
            "realistic-solar-manual:v1",
            "realistic-forest-study:v1",
        ],
    },
]


# Every family requires evidence from at least two mounted long documents. The paired
# English/Chinese prompts test the same diagram task without copying one phrasing verbatim.
E4_CHARTBOOK_CASE_FAMILIES = [
    {
        "familyId": "dev-source-selection-route",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["daa-explicit-source", "dwh-auto-scope"],
        "queries": [
            ("en", "Draw the source-selection decision route: show when an explicit material wins and when broader authorized search may begin."),
            ("zh", "请画资料选择决策图：标明显式资料何时优先，以及何时才可进入更广的授权资料搜索。"),
        ],
    },
    {
        "familyId": "dev-context-budget-funnel",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["daa-candidates", "daa-hydration", "daa-bundle", "dcc-budget-cross"],
        "queries": [
            ("en", "Create a funnel diagram for candidate retrieval, hydration and final evidence, and cross-check the retrieval budget against the change guide."),
            ("zh", "生成候选检索、资料水合到最终证据包的漏斗图，并用变更指南交叉核对检索预算。"),
        ],
    },
    {
        "familyId": "dev-layout-no-retrieval",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["dwh-layout-only", "dcc-layout-retrieve", "pre-layout"],
        "queries": [
            ("en", "Build a policy comparison diagram showing how all mounted guides treat a layout-only edit with respect to retrieval."),
            ("zh", "画一张策略对照图，说明各挂载指南如何判断纯布局编辑是否需要重新检索。"),
        ],
    },
    {
        "familyId": "dev-explicit-source-boundary",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["daa-explicit-source", "dcc-explicit-source", "pre-explicit"],
        "queries": [
            ("en", "Draw the common explicit-source boundary stated by the architecture, change and resilience documents."),
            ("zh", "根据架构、变更和韧性资料，绘制三者一致的显式资料范围边界。"),
        ],
    },
    {
        "familyId": "dev-vector-outage-behavior",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["daa-degraded", "pre-vector"],
        "queries": [
            ("en", "Create a degraded-state diagram separating manual drawing availability from blocked material search during a vector outage."),
            ("zh", "绘制向量服务故障时的降级状态图，区分仍可用的手工制图与被阻止的资料检索。"),
        ],
    },
    {
        "familyId": "dev-scoped-recovery-probe",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["pre-probe-order", "pre-degraded-retrieval", "daa-chartbook-first"],
        "queries": [
            ("en", "Draw the recovery probe sequence and show why an unfiltered successful query cannot restore chartbook retrieval."),
            ("zh", "画出恢复探测顺序，并说明为何一次不带范围过滤的成功查询不能恢复图册检索。"),
        ],
    },
    {
        "familyId": "dev-clarification-before-search",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["pre-clarify", "dcc-clarify"],
        "queries": [
            ("en", "Create a decision node for ambiguous references using both the resilience and change-control rules."),
            ("zh", "结合韧性与变更控制规则，为指代不明的请求绘制澄清决策节点。"),
        ],
    },
    {
        "familyId": "dev-version-pin-consensus",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["daa-version-pin", "pre-version-pin"],
        "queries": [
            ("en", "Draw a reopen flow showing how existing cited shapes stay pinned according to both mounted policies."),
            ("zh", "绘制已有引用图形重新打开时的版本流程，体现两份挂载策略对固定版本的一致要求。"),
        ],
    },
    {
        "familyId": "dev-chartbook-first-order",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["daa-chartbook-first", "pre-source-order"],
        "queries": [
            ("en", "Create a source-order diagram that reconciles the architecture blueprint with the resilience policy."),
            ("zh", "绘制资料检索顺序图，对齐架构蓝图与韧性策略中的当前图册优先规则。"),
        ],
    },
    {
        "familyId": "dev-recall-budget-meaning",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["daa-recall-budget", "dcc-budget-cross"],
        "queries": [
            ("en", "Draw an annotated retrieval-budget node using the architecture definition and the change guide's numeric budget."),
            ("zh", "结合架构定义与变更指南中的数值，绘制带注释的检索预算节点。"),
        ],
    },
    {
        "familyId": "dev-approval-timeline",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["daa-approval", "dwh-approval", "dcc-approve-cross", "pre-cn-approve-cross"],
        "queries": [
            ("en", "Create a four-document approval timeline for the mounted architecture, workflow, change and resilience materials."),
            ("zh", "为挂载的架构、工作流、变更和韧性四份资料绘制批准时间线。"),
        ],
    },
    {
        "familyId": "dev-degraded-claim-boundary",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["daa-degraded", "pre-degraded-retrieval", "dcc-degrade"],
        "queries": [
            ("en", "Draw the claim boundary for degraded retrieval: what remains available and what must never be presented as a successful evidence search?"),
            ("zh", "绘制降级检索的结论边界：哪些能力仍可用，哪些状态不得伪装成成功的证据检索？"),
        ],
    },
    {
        "familyId": "dev-scope-change-metadata",
        "chartbookId": "e4-drawio-build-and-recovery-dev",
        "anchorIds": ["daa-fallback-label", "dcc-metadata", "pre-source-order"],
        "queries": [
            ("en", "Create a source-scope transition diagram and label the metadata required when search expands beyond the current chartbook."),
            ("zh", "绘制资料范围变化图，并标出搜索超出当前图册时必须记录的回答元数据。"),
        ],
    },
    {
        "familyId": "val-chartbook-mounted-boundary",
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "anchorIds": ["dcg-chartbook-search", "mgv-chartbook-narrow"],
        "queries": [
            ("en", "Draw the mounted-material boundary for an active shared chartbook using both governance documents."),
            ("zh", "依据两份治理资料，绘制共享图册中已挂载资料的检索边界。"),
        ],
    },
    {
        "familyId": "val-unmounted-removal",
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "anchorIds": ["dcg-removed", "mgv-removed"],
        "queries": [
            ("en", "Create a before-and-after diagram for material removal and its effect on new retrieval."),
            ("zh", "绘制资料移除前后对新检索范围影响的对照图。"),
        ],
    },
    {
        "familyId": "val-explicit-source-scope",
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "anchorIds": ["dcg-explicit", "mgv-explicit"],
        "queries": [
            ("en", "Draw how explicit source selection constrains retrieval according to both mounted governance standards."),
            ("zh", "根据两份挂载治理标准，绘制显式资料选择如何约束检索范围。"),
        ],
    },
    {
        "familyId": "val-broader-auto-search",
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "anchorIds": ["dcg-broader-auto", "mgv-broader"],
        "queries": [
            ("en", "Create the automatic-selection branch that governs when broader material may be considered."),
            ("zh", "绘制自动选择分支，说明何时才可考虑更广范围的资料。"),
        ],
    },
    {
        "familyId": "val-rejected-draft-rules",
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "anchorIds": ["dcg-version-reject", "mgv-draft"],
        "queries": [
            ("en", "Draw a rejection history comparing the obsolete draft rules in collaboration and material governance."),
            ("zh", "绘制废弃规则对照图，比较协作治理与资料治理中被否决的草案要求。"),
        ],
    },
    {
        "familyId": "val-degraded-not-no-answer",
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "anchorIds": ["mgv-zero", "mso-degrade"],
        "queries": [
            ("en", "Create a degraded-state diagram showing why an unavailable index or interrupted collection is not a no-answer result."),
            ("zh", "绘制降级状态图，说明索引不可用或采集中断为何不能当作无答案。"),
        ],
    },
    {
        "familyId": "val-role-and-user-boundary",
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "anchorIds": ["dcg-reviewer-share", "mgv-cross-user"],
        "queries": [
            ("en", "Draw a permission boundary combining the reviewer's share limitation with the cross-user retrieval prohibition."),
            ("zh", "结合复核者的共享权限限制与跨用户检索禁令，绘制权限边界图。"),
        ],
    },
    {
        "familyId": "val-approval-timeline",
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "anchorIds": ["dcg-approval", "mso-cn-approve-cross"],
        "queries": [
            ("en", "Create an approval timeline comparing the collaboration standard and observability playbook."),
            ("zh", "绘制协作标准与可观测性手册的批准时间对照线。"),
        ],
    },
    {
        "familyId": "val-layout-no-retrieval",
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "anchorIds": ["mgv-layout", "mso-layout-retrieve"],
        "queries": [
            ("en", "Draw the shared decision for whether a layout-only edit needs retrieval in the mounted operational guides."),
            ("zh", "绘制挂载运维指南对纯布局编辑是否需要检索的共同决策。"),
        ],
    },
    {
        "familyId": "val-version-pin-consensus",
        "chartbookId": "e4-drawio-governance-and-operations-val",
        "anchorIds": ["mgv-version-pin", "mso-version-pin"],
        "queries": [
            ("en", "Create a version-pinning diagram for reopened shapes using both material-governance and observability rules."),
            ("zh", "结合资料治理与可观测性规则，为重新打开的图形绘制版本固定流程。"),
        ],
    },
]
