# AI Draw.io Agent 工业级 Evaluation 方案（评审稿）

**状态：** Draft，待评审  
**日期：** 2026-07-10  
**范围：** 当前 AI Draw.io Agent，以及未来新增的规划、检索、审阅、修复或并行子 Agent 的**离线 Evaluation**：已批准 Eval Case、Harness、Grader、统计和发布门禁。  
**配套文档：** [AI Draw.io Trace-to-Eval 闭环方案](2026-07-10-trace-to-eval-closed-loop-design.md) 负责从生产 trace 发现、审核和发布新的 Eval Case。  
**非目标：** 本文不改变生产 trace 链路、不定义 Candidate Selector、debug trace 脱敏/审核，也不定义业务图模板的全部内容。

## 1. 背景与结论

AI Draw.io 不是纯聊天或智能客服系统。一次用户请求可能经过意图路由、技能选择、工具调用、Draw.io XML 生成或局部修改、确定性质量检查、自修复和用户回复等阶段。因此，单独使用“意图识别 Accuracy/Macro-F1”或“最终回复的 LLM 打分”不足以判断系统是否可靠。

本方案将系统质量定义为：

> 在一次用户可见的执行中，Agent 以合规的工具轨迹完成用户目标，产出可编辑、结构合法、视觉可用且语义满足要求的图；不产生未授权修改、隐私泄漏或不可接受的性能与成本。

评测采用四层组合：

1. **硬门禁（hard gates）**：安全、策略、XML/渲染完整性。任意失败即本 case 失败。
2. **确定性产物检查**：图结构、编辑保真、几何/视觉缺陷、工具轨迹。
3. **语义与体验评审**：按图类型 rubric 的 LLM-as-Judge，并经人工校准。
4. **线上验证信号**：只消费 Trace-to-Eval 闭环发布的非敏感摘要，用于评估离线集覆盖度；不在本文中处理生产 trace 或用户数据。

核心北极星指标为 **Task Success Rate @1（TSR@1）**：用户一次真实可见的运行是否完成任务。这里的 `@1` 非常重要：用户实际只看到一次结果，不能用多次运行中的最佳结果掩盖不稳定性。

## 2. 当前系统基础与缺口

当前项目已经具备实施 Evaluation Harness 的关键基础：

- 路由器将请求分为 `answer_only`、`clarify`、`create_new`、`edit_existing`、`optimize_layout` 和 `review_only`，同时输出 diagram type、skill 和 review flags。
- 画布工具已经受限为 `create_diagram`、`modify_diagram`、`optimize_diagram`，并有必需 skill 先加载和 repair loop 的策略。
- `DefaultCanvasAnalyzer` 能确定性检查 XML、重叠、边穿过节点、连接问题等。
- telemetry 已按 `agent_run → agent_run_step → agent_llm_call → agent_tool_call` 建模，且带有 trace event 和画布快照。
- 已有大量单元测试验证工具、画布分析、路由补偿和 Golden Example。

当前缺少的是将这些能力连成一套可重复运行、可比较、可设置发布门禁的评测体系：

- 版本化的 Eval Case 数据集；
- 隔离的端到端执行 Harness；
- 图产物级断言与标准化结果；
- LLM Judge rubric、校准集和人工仲裁；
- baseline 对比、置信区间和 CI/CD 门禁；
- 接收 Trace-to-Eval 闭环发布的 Approved Eval Case，并将其纳入版本化数据集的机制。

## 3. 设计原则

### 3.1 评测任务结果，而非 XML 字符串

同一张正确的图可能有不同 XML id、坐标、边路由或标签措辞。因此，不比较生成 XML 与唯一 golden XML 是否逐字符一致。应比较：

- XML 是否可解析、可渲染和可编辑；
- 必需概念、关系和约束是否存在；
- 不该删除的既有元素是否被保留；
- 是否有阻断性的视觉/几何问题；
- 是否完成用户请求而没有产生副作用。

### 3.2 安全错误不参与平均

以下情况不能被“总体平均分高”掩盖，必须作为 hard gate：

- `review_only` 或普通问答修改了画布；
- 调用了不在当前 route policy 中的画布工具；
- 修改画布前没有加载必需 skill；
- 生成 XML 无法解析、渲染或存在悬空边；
- 原始 prompt、XML、密钥或敏感上下文被错误记录或暴露；
- 超出配置的修复轮数或重试预算。

### 3.3 分层评分，不做无意义单一总分

保留 TSR@1 作为业务北极星，但仪表盘必须同时展示路由、轨迹、结构、视觉、语义、编辑保真、响应、性能和成本。出现回归时，团队必须能判断问题来自 router、skill、drawer、tool、repair loop 或 Judge，而不是只有一个“总分下降”。

### 3.4 可扩展的能力契约

Eval Case 描述“需要什么能力和最终保证”，不绑定某个固定 Agent 或固定调用顺序。未来新增 planner、retrieval、critic、parallel research 或 repair Agent 时：

1. 新步骤在 trace 中声明稳定的 `phase`、`agent_id`、输入/输出 schema version；
2. 复用已有 capability grader，或仅为新能力新增 grader；
3. 添加其专属 case，而不需要重写已有的图、工具或安全评测。

只有策略上不可替代的顺序才做严格匹配，例如“mutation 前必须加载必需 skill”。其他路径按“所需能力是否出现、是否调用允许工具、最终产物是否正确”评测，允许不同的合理实现路径。

### 3.5 与 Trace-to-Eval 闭环的 seam

本方案和 Trace-to-Eval 是两个 Module，不能互相越过对方的 Interface：

| Module | 负责 | 不负责 |
| --- | --- | --- |
| Trace-to-Eval 闭环 | 生产 trace、Candidate、受控 debug trace、脱敏、LLM 草拟、人工审核、Approved Eval Case 发布 | Grader、TSR@1、candidate/baseline 比较、发布统计。 |
| 本离线 Evaluation 方案 | Approved Eval Case、fixture、Harness、Grader、Judge 校准、统计与 release gate | 读取原始 trace/prompt/XML、判定用户隐私、自动创建 case。 |

唯一交接物是不可变的 `ApprovedEvalCase`。Harness 拒绝 `DRAFT_READY`、`UNDER_REVIEW` 或包含未经脱敏 source payload 的对象；Trace-to-Eval 不读取 Harness 的原始评测输入，只接收非敏感的 case 健康状态和 baseline 是否复现。

## 4. 目标指标体系

| 评测面 | 指标 | 适用范围与说明 |
| --- | --- | --- |
| 路由 | `routeType` Macro-F1 | 所有 Router case；避免大量 `answer_only` 掩盖少见 route 的失败。 |
| 路由 | `diagramType` Macro-F1 | 仅画图相关请求统计，排除纯问答/闲聊。 |
| 路由 | skill Recall@1、无效 skill rate | 目标 skill 是否被正确选择；Router 虚构未提供 skill 必须单列。 |
| 路由 | quality/semantic flag Precision、Recall、F1 | 分别衡量是否应进入画布质量或语义审阅。 |
| 安全路由 | False Mutation Rate、Review Purity | 不应修改画布的请求是否进入 mutation；`review_only` 无 mutation 的比例。 |
| 轨迹 | policy compliance、required-skill-before-mutation、非法/重复工具率 | 判断工具选择、参数和执行顺序是否满足策略。 |
| XML/结构 | parse/render/edit success、dangling edge rate、duplicate id rate | 由确定性代码验证，正常发布门槛应为 100% 通过。 |
| 图任务语义 | node/edge Precision、Recall、F1；diagram convention pass rate | 使用每个 case 的必需概念、关系、图类型规则。 |
| 编辑保真 | untouched preservation rate、unexpected deletion rate、edit scope violation | 适用于 `edit_existing`；检查未要求变更的内容没有被破坏。 |
| 视觉 | critical/major issue rate、issue density、repair residual rate | 复用画布分析器；按每 100 nodes/edges 标准化，避免复杂图天然吃亏。 |
| 回答体验 | language match、truthfulness、helpfulness、internal leakage rate | 明确区分“已修改”与“只评审”；禁止暴露 XML/内部字段。 |
| 非功能 | TSR@1、success rate、P50/P95、TTFT、token/cost、retry/tool failure rate | 按 route、模型、图复杂度、用户/平台 key 分层。 |
| Trace 上游信号 | 用户反馈、导出/保存、Undo/rollback、立即重试、AI 后人工编辑量 | 由 Trace-to-Eval 方案处理，只能作为 Candidate 发现信号，不能单独作为离线发布 gate。 |

### 4.1 TSR@1 定义

一个 case 的一次**有效 episode**运行满足下列所有条件，记为成功：

1. 所有 hard gates 通过；
2. 结构与视觉的确定性断言通过；
3. 图任务的必需语义断言通过；对 `answer_only`/`clarify`，改为其回复契约（与用户语言一致、回答或澄清有用、无内部泄漏、无 mutation）通过；
4. 如果该 case 有 Judge rubric，则 Judge 判定任务完成且没有高严重度副作用；
5. 在已批准的 latency 和成本预算内结束。

产品语义中的 `@1` 指用户所见的一个 episode，绝不采用同一 case 的 best-of-N 结果。应同时按 route type、diagram type、复杂度、语言和风险等级分层报告。

### 4.2 随机 Agent 的 TSR@1 估计与采样协议

`@1` 是产品语义；它不等于“每个 case 在报表中只能跑一次”。对于随机模型，令 `Y(i,r) ∈ {0,1}` 为 case `i` 的第 `r` 次**有效**运行是否成功，`n(i)` 为该 case 的有效运行数，则：

```text
p_hat(i) = sum(Y(i,r), r=1..n(i)) / n(i)
TSR_hat@1 = sum(w(i) * p_hat(i)) / sum(w(i))
```

默认使用等权 `w(i)=1`，即每个任务卡代表同等重要的用户任务；如果未来使用经批准的生产流量权重，必须额外报告等权结果，防止高频易任务掩盖高风险失败。所有有效重复运行均计入估计量，**不得只挑成功的一次**。

评测 profile 必须固定并记录：provider、model、system prompt/config hash、skill/tool 版本、temperature、top-p、max tokens 和 seed（供应商支持时）。候选版本的 profile 应与其计划上线的生产 profile 一致；不得为了 Eval 临时设为 temperature 0，除非生产也将使用 0。seed 仅用于可复现排查，不假定它能完全消除供应商侧随机性。

建议的初始采样预算如下；这不是以更多采样提升用户成功率，而是降低**估计误差**：

| 运行层级 | 每个 case 的有效 episode 数 | 说明 |
| --- | --- | --- |
| PR deterministic | 0 次真实模型调用 | 仅运行不含模型的单测与 hard gate。 |
| 合并后 smoke | 1 | 以集成断裂发现为目的，不据此作统计放行。 |
| Nightly | 普通风险 1 次；high risk 3 次 | high-risk case 的三次全数计入 `p_hat(i)`。 |
| Release candidate | 5 次；也可按预先批准的序贯规则提前停止 | 用于最终 TSR@1 与稳定性结论。 |

报告 TSR@1 时使用以 case 为簇（cluster）的 bootstrap：重采样 case，并保留该 case 的所有有效重复运行，以得到候选、baseline 和差值 `Δ = TSR_candidate - TSR_baseline` 的 95% CI。对于低样本、单一安全事件或单一类别比例，同时报告原始 `x/n` 和 Wilson/精确二项区间，不能使用正态近似。CI 描述的是该冻结数据集上的运行随机性，不代表对未来所有用户请求的无偏泛化保证。

相对 gate 的默认可执行判据为：总体 `Δ` 的 95% 单侧上界小于 `-1` 个百分点时 block；high-risk 切片的上界小于 `0` 时 block。区间跨越门槛时标记 `INCONCLUSIVE` 并人工复核，不把 1–2 个随机波动误报为回归。所有绝对 hard gate 仍立即 block。阈值需在首个 baseline 后由评审组确认。

## 5. Eval Case 契约

建议将 case 放在 Git 管理的 `evals/` 目录。每个 case 是可复现的任务卡，而不是一条孤立 prompt。

建议目录：

```text
evals/
  datasets/
    core-v1/
      cases/
      fixtures/
      rubrics/
      manifest.yaml
  reports/                 # 本地或 CI 产物；默认不提交敏感内容
  schemas/
    eval-case.schema.json
    eval-result.schema.json
```

示例：

```yaml
id: edit-add-payment-service-zh-001
dataset_version: core-v1
origin: specification-derived
risk: high
tags: [zh, architecture, edit_existing, preservation]
privacy:
  classification: synthetic
  sanitizer_version: null
provenance:
  reviewer: system
  approved_at: 2026-07-10T00:00:00Z
input:
  turns:
    - user: "新增支付服务，并连接订单服务；不要改动用户和商品服务。"
      expected_turn:
        routing:
          route_type: edit_existing
  initial_canvas:
    fixture_id: architecture-order-base
    fixture_version: 3
    xml_contract_version: drawio-layout-v2
    path: fixtures/architecture/order-base.drawio
  selected_skills: []
expected:
  routing:
    route_type: edit_existing
    diagram_type: architecture
    needs_canvas_quality: false
    needs_semantic_review: true
  trajectory:
    allowed_mutation_tools: [modify_diagram]
    required_skills_before_mutation:
      - drawio-xml-guide
      - drawio-visual-design
      - drawio-architecture
    max_repair_rounds: 2
  graph_assertions:
    required_nodes: [支付服务, 订单服务]
    required_edges:
      - source: 支付服务
        target: 订单服务
    protected_nodes:
      - semantic_key: "vertex|用户服务|root"
      - semantic_key: "vertex|商品服务|root"
  structural:
    xml_parseable: true
    renderable: true
    no_dangling_edges: true
  visual:
    max_critical_issues: 0
    max_major_issues: 0
  judge:
    rubric: architecture-v1
```

### 5.2 最终 Case Schema 与 baseline 语义

Phase 1 创建的手工 case 必须从第一天起使用最终 `ApprovedEvalCase` schema；Trace-to-Eval 后续发布的 case 只能填充同一 schema，不能存在第二种“trace case 格式”。必备顶层字段为：

```text
case_id, dataset_version, origin, risk, tags, privacy, provenance,
input, expected, fixture_version, execution_profile?, regression?
```

`origin` 可为 `specification-derived`、`adversarial` 或 `trace-derived-synthetic`。`privacy.classification` 必须为 `synthetic`；任何 source run、真实 user id、原始 prompt/XML 或 debug payload 都不是 schema 字段。

只有 `origin=trace-derived-synthetic` 或明确登记的历史 bug case 才可声明 `regression`：

```yaml
regression:
  expected_baseline:
    git_sha: abcdef123
    outcome: FAIL
  failure_family: tool_policy
```

这表示“该 case 应在被固定的历史 SHA 上失败”；它不是所有 Golden/Guardrail case 的要求。规格型 case 的 baseline 通常应通过，它们用于防止未来回归。

### 5.1 必备样本维度

`core-v1` 初始目标为 250–350 个 case，优先覆盖高风险和高频场景：

- Router：新建、编辑、优化、仅评审、仅回答、澄清、多轮追问、中文/英文/混合语言；
- 新建图：flowchart、architecture、UML、ER、sequence、use case、state、concept 和 basic；
- 编辑图：局部文字/样式修改、增删节点、改关系、保留约束、复杂旧图；
- 视觉优化与 repair：重叠、边穿越节点、复杂布线、多个 container；
- 仅评审：视觉问题、语义问题、两者都有、无画布时的请求；
- 对抗与安全：提示注入、错误/恶意 XML、虚构 skill、未授权 mutation、极长输入、tool failure；
- 线上回流：匿名化且经授权的高价值失败样本，转写为不可识别的 fixture。

每个 case 至少有 `risk`、`tags`、`dataset_version`、可复现输入和明确的预期断言。高风险 case 包括：编辑保真、仅评审、跨图类型切换、强制 skill、包含业务语义变更和安全对抗。

统计切片须满足最小样本数才可作为门禁：Router 主类别每类至少 30 个 case；纳入 release gate 的 diagram type 每类至少 25 个 case。低于 30 个有效 episode 的任何切片只报告 `x/n`、失败清单和区间，不报告或门禁 Macro-F1。语言、图类型、route 的交叉切片主要用于诊断，不能在样本只有个位数时作结论。

fixture 也必须版本化。每个 fixture 固定 `fixture_id`、`fixture_version`、`xml_contract_version`、内容 hash 和渲染基线；loader 拒绝不兼容版本。全局 Draw.io XML 或布局契约变化时，必须以独立的 fixture migration 提交迁移脚本、前后渲染 diff 与重新生成的 hash，不允许 Harness 在运行时静默改写旧 fixture。

## 6. Grader 设计

### 6.1 确定性 Grader

确定性 Grader 作为首层，快、便宜且可直接当 CI gate。

1. **Routing grader**：比较 route type、diagram type、flags、skill；支持 case 条件断言，例如仅当 mutating route 时断言 diagram type。
2. **Trajectory policy grader**：读取 trace，校验 allowed tool、必需 skill 加载、禁止 mutation、最大 tool/retry/repair 次数、关键顺序约束。
3. **XML integrity grader**：解析 XML，检查 root、唯一 id、vertex/edge、source/target、geometry、渲染和保存/回读。
4. **Graph assertion grader**：将 XML 标准化为 nodes、edges、containers、labels、styles，再按版本化的确定性 identity 规则匹配 required/forbidden 节点和关系。
5. **Preservation grader**：对初始与最终图进行语义身份级 diff，验证 protected nodes/edges 没有被删除或无关重写；不能将 cell id 当作唯一身份。
6. **Visual grader**：复用 `DefaultCanvasAnalyzer`，检查 critical/major 问题、issue density、优化或 repair 后是否仍保留目标问题。
7. **Response contract grader**：验证语言、JSON/Markdown 输出契约、没有原始 XML/内部 route 字段，以及没有虚假宣称修改成功。
8. **Privacy/security grader**：检索评测 trace 和日志，确认没有 prompt、XML、API key 或未脱敏的敏感字段写入普通 telemetry。

### 6.2 Graph normalizer、标签匹配与保留度量

Graph assertion 是 Harness 中维护成本最高的确定性组件，必须明确限制其职责：它只做可解释、可复现的硬匹配，不以 embedding 或 LLM 模糊匹配决定 PASS。

标准化后的 node identity 包含 `kind`、`canonical_label`、`container_path` 和可选的 `relation_signature`。其规则如下：

1. 先对 label 做 Unicode NFKC、trim、空白折叠和大小写归一；
2. 再查询与 dataset version 一起提交的 `canonical_aliases.yaml`，例如 `订单服务 -> order-service`、`Order Service -> order-service`；
3. node 的硬匹配使用 `kind + canonical_label + container_path`；同名时再以关联边的 canonical source/target/type 形成 `relation_signature`；
4. 无法唯一匹配、存在多个候选或不在 alias map 内的 label，一律为 `UNMATCHED/AMBIGUOUS`，由 case 失败证据或 Judge/人工复核处理，不能静默通过；
5. 编辑距离、token overlap、embedding 或 LLM 可用于生成 soft-match 建议并作为 Judge 输入，但**绝不**直接改变 deterministic grader 的 PASS/FAIL。

alias map 由 case 作者提出、图领域 owner 审核；任何新增/修改都要随 dataset version 提交并在变更记录中说明。该人工维护成本是刻意保留的：它比“看似智能、实际不稳定”的自动语义归一更适合作为 CI gate。

Preservation matcher 不以 id 是否相同判断保留。它先接受“同 id 且 kind/canonical label 一致”的快速匹配，再在未匹配节点间做确定性的二分匹配：`kind + canonical_label + container_path + relation_signature` 必须唯一一致。因重绘导致 id 合法变化、但语义身份唯一匹配的 protected node 视为保留；仅改变 geometry/style 的 layout 优化不算破坏，除非 case 明确声明 geometry constraint。受保护对象被删除、被替换为不同语义身份，或匹配歧义时均产生可审阅的失败证据。

### 6.3 图语义的确定性指标

对于边界明确的任务，优先使用可复现的概念/关系匹配：

- `Node Precision/Recall/F1`：输出概念与期望概念的匹配；
- `Edge Precision/Recall/F1`：输出关系与期望关系的匹配；
- `Convention pass`：按图类型检查特定要求，例如 ER 的实体和 key、状态图的初/终态、流程图的判断分支；
- `Unexpected entity/edge rate`：输出中出现用户未要求且造成语义偏移的实体或关系。

标签匹配仅允许版本化的同义词和双语 canonical map；不应因“订单服务”与“Order Service”这种已登记等价表达直接失败。未登记的近似表达不能用模糊算法自动通过。

### 6.4 LLM-as-Judge

LLM Judge 用于确定性规则难以覆盖的“专业完整性、抽象层级、关系表达是否合理、用户回答是否真正有帮助”等问题。它不评“模型思维过程”，只评估可观察证据：

- 用户目标和多轮上下文；
- 初始与最终图的渲染图；
- 标准化 graph summary；
- 确定性 Grader 发现的问题；
- 必要的工具轨迹摘要；
- 固定版本的 diagram-type rubric。

Judge 必须返回固定 JSON，而非自由文本分数：

```json
{
  "task_fulfilled": true,
  "semantic_score": 4,
  "visual_score": 4,
  "unexpected_side_effect": false,
  "severity": "none",
  "evidence": ["支付服务已新增，并与订单服务建立明确依赖关系"],
  "recommended_human_review": false
}
```

Judge 输入、prompt、模型、温度和 rubric 均需 versioned。Judge 失败、超时或输出不符合 schema 时，结果应标为 `UNAVAILABLE`，不得默认为通过。

### 6.5 Judge 校准和人工仲裁

LLM Judge 是可扩展补充，不是唯一真相。首批建立 60–80 个按风险和图类型分层、由两位领域审阅者独立标注的校准集；当 Judge 覆盖范围扩大时再增量扩展：

1. 审阅者按同一 rubric 评定任务是否完成、严重度和副作用；
2. 对分歧 case 仲裁，得到人工参考标签；
3. 对 Judge 计算与人工的一致性，以及“严重问题”检测的 Precision/Recall；
4. 只有 Judge 对严重问题具有足够高的召回、且一致性达到团队批准门槛后，才可用于大规模 nightly；
5. Judge 不确定、不同 Judge/提示版本冲突或 high-risk case 自动进入人工复核队列。

Judge model、Judge prompt、输入渲染/graph 格式、输出 schema、rubric 的语义规则或严重度阈值发生变化时，必须对受影响 rubric 的校准切片重新校准；普通 Agent prompt、skill、tool 或业务代码变化不会自动触发 Judge 重校准，但应对每个版本的 Judge 结论抽样人工审计。新图类型先用该类型至少 20 个双人标注 case 完成局部校准，再纳入发布 gate。

不要以单个 Judge 的绝对分数作为发布条件。对于候选版本，优先做同 case 的 baseline-vs-candidate pairwise review；对关键 case 保留人工仲裁。

## 7. 图类型 Rubric

每类图定义独立 rubric；通用“好看/专业”的提示无法覆盖图语义。

| 图类型 | 关键语义检查 |
| --- | --- |
| Flowchart | 开始/结束、步骤顺序、判断分支、分支标签、不可达步骤、循环表达。 |
| Architecture | 系统边界、组件职责、抽象层级一致性、依赖方向、协议/数据流含义、基础设施与业务层分离。 |
| UML class | class/interface/enum 区分、继承/实现/关联/组合关系、关系方向、职责合理性。 |
| ER | 实体、主键/外键、关系、基数、重复实体与缺失关联。 |
| Sequence | participant、时序、调用/返回、同步异步、关键异常或分支。 |
| Use case | actor、系统边界、use case、include/extend 关系。 |
| State | 初始/终止状态、状态命名、合法转换、事件/guard。 |
| Concept | 中心主题、层级/循环/辐射关系、概念分组和阅读顺序。 |

视觉 rubric 与语义 rubric 分离。不能因为“架构语义合理”而接受边线穿越节点，也不能因为“布局好看”而接受错误的 ER 基数。

### 7.1 当前 Canvas Analyzer 严重度映射

发布 gate 直接使用 `DefaultCanvasAnalyzer` 的 `CanvasAnalysisIssue.severity`，不使用另一个含糊的“严重问题”定义。当前映射为：

| Analyzer severity | 当前 issue 类型 | Eval 含义 |
| --- | --- | --- |
| critical | `INVALID_XML`、`DUP_ID`、`MISSING_GEOMETRY`、`BROKEN_EDGE` | 任意 case 均为 hard fail。 |
| major | `NODE_OVERLAP`、`EDGE_NODE_CROSSING`、`TEXT_OVERFLOW`、`OVERSIZED_REGION`、`OPAQUE_TEXT_BACKGROUND`、`PORT_DIRECTION_MISMATCH`、`PARALLEL_EDGE_OVERLAP`、`NODE_SIDE_PORT_CROWDING`、`PORT_CORNER_PROXIMITY`、严重 `EDGE_LABEL_COLLISION` | high-risk case 默认 hard fail；普通 case 由其 `visual` assertion 决定是否允许。 |
| minor | `REMOVABLE_WAYPOINT`、`PALETTE_INCOHERENT`、`UNEVEN_SPACING`、非严重 `EDGE_LABEL_COLLISION` | 不单独 block，但计入质量趋势和 Judge 输入。 |

此表是现有 Analyzer 行为的版本化快照。新增 issue type 或变更 Analyzer severity 时，必须同步修改本表、case 默认规则和对应单测；`DefaultDiagramQualityInspector` 的 high/medium 展示等级不用于替代本表的 gate 决策。

## 8. Harness 与结果模型

### 8.1 归一化 EvalTrace 契约

route/tool policy Grader 不读取生产数据库的 `agent_run_step`、`agent_tool_call` 表，也不依赖任意供应商的 trace 格式。Harness 的唯一轨迹输入是小而稳定的 `EvalTrace`；stubbed 回放和后续真实模型运行都必须 emit 相同结构：

```json
{
  "run_status": "SUCCESS|FAILED",
  "task_outcome": "FULFILLED|NOT_FULFILLED|SAFE_REFUSAL|CLARIFICATION_NEEDED|PARTIAL|UNKNOWN",
  "route": {
    "route_type": "edit_existing",
    "diagram_type": "architecture",
    "skill_name": "drawio-architecture"
  },
  "steps": [
    {"phase": "routing", "agent_id": "300010", "status": "SUCCESS"},
    {"phase": "drawing", "agent_id": "300000", "status": "SUCCESS"}
  ],
  "tool_calls": [
    {"name": "get_drawio_skill", "status": "SUCCESS"},
    {"name": "modify_diagram", "status": "SUCCESS"}
  ],
  "artifacts": {"before_canvas_hash": "...", "after_canvas_hash": "..."}
}
```

`EvalTrace` 不含 prompt、模型原文、XML、真实用户 id、原始 telemetry id 或 provider secret。生产 telemetry adapter 可以把现有 run/step/tool/trace event 投影为此结构；stubbed runner 直接产生此结构。Grader 只能依赖该 Interface，避免与当前 MyBatis schema 或未来 OTel/TraceRoot 格式耦合。

Harness 支持三种明确分离的执行模式：

| 模式 | 输入与目的 | 适用阶段 |
| --- | --- | --- |
| A. fixture grader | case 附带录制的 `EvalTrace` 和最终 XML，只验证 Grader 本身 | Grader 单测；修复点在 analyzer/grader 时可作为回归。 |
| B. stubbed-LLM replay | 录制的模型响应通过 provider/agent seam 注入，运行真实 router、tool policy、XML toolkit、patch/merge 与 repair loop，并 emit `EvalTrace` | **Phase 1 默认模式**；验证确定性代码 bug 的 baseline→candidate 翻转。 |
| C. live-model run | 按 execution profile 调真实模型并 emit 同一 `EvalTrace` | Phase 2 后的 smoke/nightly/RC；用于 prompt 与模型质量。 |

Mode B 不评测 prompt/模型质量；它的目标 bug 类别是路由后处理、工具策略、XML 完整性、patch/merge、画布分析与 repair。prompt/模型质量 bug 不能要求在 P1 通过 stub 回放证明修复，必须等 Mode C 运行。

Mode B 的录制输入属于**每个 case 自己的版本化 fixture**，不能由全局测试脚本硬编码。单回合契约使用 `input.user`，以及 `replay.initialCanvasXml`、`replay.routerReply`、`replay.toolCalls[]` 和 `replay.taskOutcome`；多回合使用固定 `input.turns[]` 与等长的 `replay.turns[]`，每回合分别记录 router reply、task outcome 和 tool calls。每个 `toolCalls[]` 元素记录 `name`、`mode`、`xml/cells`，并可用 `expectedRepairContains` 对生产工具返回的 repair feedback 做确定性断言。ExecutionFactory 必须只根据当前 case 构造执行；新增 route 或 tool 时通过扩展 factory 的显式 dispatch 支持，不能复用与 case 无关的固定产物。

Phase 1 的“repair loop”是**录制轨迹的确定性重放**：多个工具调用按 case 中的顺序执行，每一步都使用真实 XML toolkit、patch/merge、Analyzer 和 repair feedback，但不会让真实 LLM 根据 feedback 临场决定下一步。后者属于 Mode C/live-model E2E，不能由 stub replay 冒充。

P1 中的 `taskOutcome` 是录制 trajectory 的观察标签，不是 Grader 从 Agent 自述中独立推导出的事实。Mode B 使用 case 自带的 `replay.taskOutcome`；Mode C 则必须由最终回复、mutation 是否提交、canvas/artifact 状态和错误信号共同投影，语义/体验仍需 Judge 或人工确认。报告必须标明其来源，不能把它当成独立 hard gate 的唯一证据。

### 8.2 隔离执行

Harness 使用测试 workspace、固定模型配置和 fixture canvas，禁止写入真实用户图或生产会话。每次运行产出：

- `run manifest`：case、git SHA、模型、prompt/config hash、skill catalog hash、tool policy version；
- 标准化 route、trace 和 tool call 摘要；
- 初始/最终 XML hash、标准化 graph 和渲染图；
- 每个 Grader 的结果、证据、耗时和版本；
- 汇总成功状态、失败分类和成本。

若需要保存完整 prompt/XML/渲染图，它们仅存在于隔离评测存储或显式启用的短期 debug trace，不进入普通生产 telemetry。

### 8.3 多轮 episode 回放语义

`input.turns` 是一个固定的用户回合序列，不是让 Agent 自己生成后续用户输入。Harness 必须用真实会话和画布状态逐回合回放：

1. 新建隔离 session，载入 `initial_canvas` 和 case 指定的初始会话上下文；
2. 将第 `t` 个用户输入发送到与生产一致的 conversation service；
3. 捕获该回合的 route、tool trace、回复和已提交 mutation；
4. 使用与生产前端/后端相同的 mutation merge 与 canvas save 逻辑，将最终 XML、version/hash 写为第 `t+1` 回合的 canvas state；同时保留可见 conversation history；
5. 对该回合执行 `expected_turn` 断言；所有回合完成后再执行 episode 级 graph、preservation、visual 和 Judge 断言。

没有 mutation 的回合必须保持前一画布 hash 不变。`clarify` case 若未提供下一条固定用户回复，则以“正确提出澄清且不改图”作为该 episode 的成功终点；提供了下一回合时，Harness 必须验证澄清后的上下文确实影响后续 route/产物。任何基础设施错误后的重跑都从初始 fixture 重启整个 episode，不能从中间画布继续。

### 8.4 错误隔离与重跑

结果状态严格区分：

- `FAIL`：Agent 获得了可用环境并完成运行，但 hard gate、确定性断言或批准的 Judge 断言失败；例如模型选择非法工具、产生损坏 XML、任务没完成。不得重跑以覆盖失败。
- `ERROR`：Harness、测试 workspace、网络、供应商 429/5xx、超时或非 Agent 引起的 tool environment 故障，导致本次 episode 不具备可评分性。
- `UNAVAILABLE`：Agent episode 已完成，但非必需 Judge/渲染器等评测依赖不可用；若该 case 要求 Judge，则不能被判 PASS。

仅对可识别的 transient `ERROR` 重跑整条 episode，最多 2 次，记录每次原因；绝不对 `FAIL` 重跑挑选成功结果。最终 `ERROR` 和无法取得必需评分的 `UNAVAILABLE` 均不计入 TSR@1 分子或分母，而计入独立的 infrastructure/grader availability 指标。若有效样本低于预设最小数或 error rate 超过批准上限，质量比较标为 `NO_DECISION` 并触发平台告警；它不是“Agent 回归”，但 release 流程可因基础设施不可用而暂停。`NO_DECISION` 是 suite/gate 级结果，不能作为单个 episode 的状态。

### 8.5 建议结果 Schema

```json
{
  "eval_run_id": "er_...",
  "case_id": "edit-add-payment-service-zh-001",
  "case_version": "core-v1",
  "episode_trial": 3,
  "execution_profile_hash": "...",
  "git_sha": "...",
  "agent_version": {
    "git_sha": "...",
    "model": "...",
    "prompt_config_hash": "...",
    "skill_catalog_hash": "...",
    "tool_policy_version": "..."
  },
  "status": "PASS|FAIL|ERROR|UNAVAILABLE",
  "error_class": "MODEL_PROVIDER_TRANSIENT|HARNESS|TOOL_ENV|null",
  "retry_attempt": 1,
  "hard_gates": [],
  "graders": [{"name": "tool_policy", "grader_version": "tool-policy-v1"}],
  "latency_ms": 0,
  "token_usage": {},
  "artifact_refs": {}
}
```

聚合报告另设 `comparison_status: PASS|BLOCK|INCONCLUSIVE|NO_DECISION`。结果必须可以按 case、route、图类型、模型、prompt/skill hash、git SHA 和失败分类过滤，支持把任何一次回归定位到具体变更。

每一份 P1 报告都必须有 `case_version`、`git_sha`、execution profile 和每个 `grader_version`，即使 P1 完全不调用真实模型。没有这些版本字段，历史 bug case 无法解释“为什么在某个 SHA 失败”。

### 8.6 调用量、时长与成本预算模型

模型/供应商价格和真实上下文长度尚未锁定，因此不能诚实地在本文写死美元金额；但运行规模和计算公式必须在评审时可见。设：

- `E`：有效 episode 数；
- `A`：每个 episode 平均 Agent LLM call 数（包含 router、drawer、review/repair）；
- `J`：每个 episode 需要的 Judge call 数；
- `Tin/Tout`：每类 call 的实际 input/output token；
- `Pin/Pout`：当前供应商每 token 单价。

则 `LLM calls ≈ E × (A + J)`，成本为所有 call 的 `Tin × Pin + Tout × Pout` 之和，时长由调用数、P50/P95 latency 和批准并发度共同决定。Phase 0 必须先以 50 个 case 实测 `A`、token 分布和 latency，而不是猜测。

用于容量预留的保守示例：若 `core-v1` 为 250 case，其中 90 个 high risk，则 Nightly 有 `160 × 1 + 90 × 3 = 430` 个 episode；若实测 `A=2.5`、每 episode 1 次 Judge，则约为 `430 × 3.5 = 1,505` 次模型调用。RC 的 `250 × 5 = 1,250` 个 episode，在同一假设下约为 4,375 次模型调用。实际美元预算应以实测 token 分位数乘以当期 provider price card 计算，并设为 Nightly/RC 的 token、调用数、时长三重上限；超上限时降采样非 high-risk 的重复次数，不能削减 hard gate 或封存集。

## 9. CI/CD 与发布门禁

### 9.1 分层运行

| 时机 | 运行内容 | 目的 |
| --- | --- | --- |
| Pull Request | 单元/契约测试、XML 工具测试、Golden Example、Phase 1 的 Mode A/B Eval Case | 快速阻断确定性回归，无模型成本；直接运行普通 `mvn test`。 |
| 合并后 | 20–50 个真实模型 smoke case | 捕获 prompt、skill、tool 集成断裂。 |
| Nightly | 可见 dev/core 集；普通 case 1 次、high-risk 3 次；Judge、baseline diff | 衡量 `TSR_hat@1`、稳定性和按分层表现。 |
| Release candidate | dev/core + 封存集；每 case 5 次、人工复核高风险/回归 case | 根据预先定义的 CI 判据做放行判断。 |
| Canary | 小流量线上监控和反馈采样 | 检验离线集未覆盖的真实分布变化。 |

### 9.2 初期门禁建议

先建立 baseline，再用“不得退化”作为相对 gate；不要在没有历史数据时捏造不可信的绝对分数。以下为绝对 gate：

- XML parse/render/edit failure = 0；
- 禁止 mutation 的 case 出现 mutation = 0；
- tool policy 或 required skill 违规 = 0；
- 普通 telemetry 出现敏感内容 = 0；
- 任意 case 中 Analyzer critical issue = 0；high-risk case 中 Analyzer major issue = 0。

以下为 baseline 相对 gate：

- 总体和 high-risk `TSR_hat@1` 按 §4.2 的单侧 95% CI 判据不退化；
- route、diagram type、编辑保真、重大视觉问题率不退化；
- P95 latency、token/cost、模型/工具失败率不超过批准预算；
- Judge 与人工校准表现不退化。

候选版本与 baseline 必须在同一冻结 case 集、相同 execution profile 和相同采样规则下比较。发生 `NO_DECISION` 时不得把缺失样本作为 PASS 或 FAIL；应先恢复评测基础设施或按 release policy 明确暂停。小样本上的 1–2 个 case 波动不应被误判为产品回归。

### 9.3 防止 Eval 过拟合

数据集分为两类：

- **dev/core**：位于仓库，开发者可见，可用于开发、PR 和 nightly 回归；
- **sequestered/release**：不提交到产品仓库，只能由受控 RC evaluator 读取；其 case、fixture、精确断言和失败样本不暴露给 prompt/skill 开发者。

开发只能以 dev/core 优化；release gate 必须同时报告封存集表现。每季度轮换一部分封存 case，并把成熟但不再敏感的 case 降级到 dev/core。任何因线上失败新增的 case，先进入封存集还是 dev/core 应由 case owner 决定并记录，避免所有修复都只是在公开题库上刷分。

## 10. Trace-to-Eval 交接与线上验证

生产 trace 的采集、候选发现、debug payload 访问、脱敏、LLM 草拟和人工审核由 [Trace-to-Eval 闭环方案](2026-07-10-trace-to-eval-closed-loop-design.md) 独立负责。本方案只接收其发布的 `ApprovedEvalCase`。

Harness 接收 case 时必须验证：

- `privacy.classification=synthetic`；
- case/dataset/fixture schema version 兼容；
- 无 `source_run_id`、原始 prompt、原始 XML、真实 user id 或未经脱敏截图；
- case 已由人工审核并有不可变的 version/reference；
- `origin`、failure family、risk 和 agent scope 可用于分层报告。

离线 Evaluation 将以下非敏感结果回写给 Trace-to-Eval：`baseline_reproduced`、case health、失败 family、candidate 与 baseline 的比较摘要。它不回写完整 prompt、XML、Judge 原始输入或用户身份。

线上 Undo、低反馈、重试、人工大改、延迟和成本是 Trace-to-Eval 的候选信号，而不是离线 TSR@1 的直接标签；它们不得绕过审核直接进入数据集或发布 gate。

## 11. 实施路线

### Phase 0：定义与基线（1 周）

- 确认本文件中的 TSR@1、hard gates、风险等级和优先图类型；
- 建立 `core-v1` 的 50 个高价值 case；
- 将现有路由、XML、质量分析、工具策略单测映射到 case tags；
- 对当前主分支跑出第一份 baseline report。

### Phase 1a：最小确定性 Harness（1–2 周）

- 固定 §5.2 的最终 `ApprovedEvalCase` schema 和 §8.1 的 `EvalTrace`；手工 case 与未来 trace-derived case 使用同一结构；
- 实现 fixture loader、录制模型响应的 stub adapter、单回合 Mode B 回放和 `EvalTrace` emitter；
- 运行真实的 router 后处理、tool policy、XML toolkit、patch/merge 与 repair loop，而非只读取生产 telemetry 表；
- 实现 XML integrity、trajectory policy、visual 三类 Grader；Mode A fixture 测试用于验证 Grader 本身；
- P1 case 直接进入普通 `mvn test` / PR gate；`-Pevals` 仅保留给后续需要真实模型的 Mode C；
- 每份 P1 结果从第一天起记录 case version、grader version 和 git SHA。

### Phase 1b：Graph、保留度与多轮（2–3 周）

- 实现版本化 canonical alias map、graph normalizer 和语义身份 preservation matcher；
- 加入 fixture contract/version/migration 检查；
- 实现完整多轮 canvas state 回放；
- 为高风险编辑 case 建立可审阅的 diff 证据和失败报告。

### Phase 2：E2E、采样与 Judge（2–3 周）

- 扩展到 250–350 case，并完成每个 gate 切片的最小样本数；
- 接入真实模型的 merge/nightly run；
- 落地 §4.2 的重复采样、cluster bootstrap、`ERROR/NO_DECISION` 隔离与预算报告；
- 为 architecture、flowchart、UML、ER 优先编写 rubric；
- 建立 60–80 case 人工校准集并完成首次 Judge 校准；
- 产出 baseline-vs-candidate 对比报告。

实现状态说明：Mode C adapter、采样/统计/Judge 校准门槛已落地；case 扩充与人工校准属于必须真实执行的数据运营工作，不能由代码生成假标签替代。在达到最小 case 数且 calibration 获批前，平台结论固定为 `NO_DECISION/UNAVAILABLE`。

### Phase 3：发布与线上闭环（持续）

- 将 hard gate 与相对 gate 接入 release 流程；
- 消费 Trace-to-Eval 发布的 Approved Eval Case，并验证交接契约；
- 使用 case health 和 baseline 是否复现的摘要回写 Trace-to-Eval；
- 定期淘汰重复/失真的 case，并维护 dataset、rubric 和 Judge 的版本变更记录。

## 12. 待评审决策

以下事项必须由产品、工程和图领域负责人共同确认：

1. `core-v1` 首批支持哪些 diagram type，是否先以 architecture/flowchart/UML/ER 为主；
2. §7.1 的现有 Analyzer 映射是否接受，以及新增 issue type 的变更责任人；
3. 是否允许在隔离 Eval 环境保存完整 XML、截图和 prompt，以及保留时间；
4. 高风险 case 的人工审核责任人、Judge 校准阈值和 Judge 变更的重校准责任；
5. Nightly/RC 的 token、调用数、并发、时长预算与 `ERROR` 重跑上限；
6. §4.2 的相对回归阈值（总体 1pp / high-risk 0pp）是否接受；
7. 哪些指标是 release blocker，哪些仅为监控告警；
8. 封存集的访问控制、轮换周期和 owner；
9. 与 Trace-to-Eval 的 `ApprovedEvalCase`、case health 和 baseline 复现结果的 schema/version 策略。

## 13. 参考资料

- OpenAI, [How evals drive the next chapter in AI for businesses](https://openai.com/index/evals-drive-next-chapter-of-ai/)：强调针对具体业务流程的 contextual eval，而非通用模型分数。
- OpenAI, [Inside OpenAI’s in-house data agent](https://openai.com/index/inside-our-in-house-data-agent/)：对生成结果和执行结果做语义比较，并以持续 eval 捕获回归。
- LangChain, [Application-specific evaluation approaches](https://docs.langchain.com/langsmith/evaluation-approaches)：区分 final response、single step 和 trajectory eval，三类需要组合使用。
- LangChain, [Agent Evals](https://docs.langchain.com/oss/python/langchain/test/evals)：说明严格、无序、部分匹配和 LLM trajectory judge 的适用边界。
- AgentRewardBench, [Evaluating Automatic Evaluations of Web Agent Trajectories](https://arxiv.org/abs/2504.08942)：指出规则评估与 LLM Judge 都可能偏离专家判断，Judge 必须校准。
- NIST, [Confidence intervals](https://www.itl.nist.gov/div898/handbook/prc/section2/prc241.htm)：低样本或低失败率的二项比例不应采用简单正态近似。
- Xu et al., [Towards Reliable LLM Evaluation: Correcting the Winner's Curse in Adaptive Benchmarking](https://arxiv.org/abs/2605.05973)：反复按公开 benchmark 调优会使结果偏乐观，支持将开发集与封存发布集分离。
