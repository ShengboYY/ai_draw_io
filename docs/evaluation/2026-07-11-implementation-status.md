# Evaluation 与 Trace-to-Eval 实施状态

**更新日期：** 2026-07-11

本文按统一实施顺序记录每个阶段的验收证据。阶段只有在实现、方案对照、测试和独立 commit 都完成后才标记为完成。

## Phase 0：当前确定性链路收口

**状态：完成**

| 方案要求 | 实现证据 | 结果 |
| --- | --- | --- |
| case 使用最终统一 schema | `EvalCaseDefinition` 同时承载 input、expected、privacy 和 Mode B replay | 完成 |
| Mode B 按 case 自身执行 | `ModeBReplayExecutionFactory` 只读取当前 case 的 input/replay；没有全局 Payment Service 脚本 | 完成 |
| 验证异构数据集 | `edit_existing + modify_diagram` 与 `create_new + create_diagram` 两个磁盘 case 均经 batch runner 执行 | 完成 |
| batch runner 遍历磁盘 case | `EvalBatchRunner` 遍历 YAML，并通过注入的 ExecutionFactory 执行 | 完成 |
| JSON/Markdown 报告 | 报告包含 case/version、git SHA、状态、耗时、grader/version、证据和错误 | 完成 |
| 单 case 错误隔离 | loader、privacy、factory 或 harness 异常记录为 `ERROR`，同批其他 case 继续 | 完成 |
| 独立 XML integrity | 直接检查 XML 解析、root、唯一 id 和悬空 edge，不依赖 visual severity | 完成 |
| 普通 PR 测试 | app 默认 Maven 测试只运行无模型 evaluation 测试 | 完成 |
| taskOutcome 来源明确 | Mode B 来自 case 录制标签；Mode C 必须由回复、mutation、artifact 和错误投影 | 完成 |

验证命令：

```text
mvn clean test
```

阶段完成时结果：9 个 evaluation 测试、2 个既有 infrastructure 测试通过。

### 明确不属于本阶段

- 扩展到 10–20/50 个高价值 case；
- graph semantic assertion、preservation matcher 和完整多轮 episode；
- 真实模型、Judge、重复采样和统计门禁；
- 自动 Candidate Selector 和 LLM 草拟。

这些内容分别由后续数据集建设、Graph/多轮、Live-model Evaluation 和 Trace-to-Eval 阶段负责，不能被视为 Phase 0 的遗漏。

## Phase 1：确定性 Evaluation 基础

**状态：完成**

| 方案要求 | 实现证据 | 结果 |
| --- | --- | --- |
| 统一 `ApprovedEvalCase` 契约 | `EvalCaseDefinition` 固定独立 case/dataset/fixture version，以及 provenance/executionProfile/regression/input/expected/replay；loader 拒绝非 `fixture-v1` 和非 synthetic case | 完成 |
| 归一化 trajectory | `EvalTrace` 独立于生产 telemetry，记录 routing、step、tool 和 canvas hash，routing flags/answer mode 可断言 | 完成 |
| case-driven Mode B | 每个 YAML 自带 router reply、initial canvas 和 `toolCalls[]`；factory 不共享固定业务脚本 | 完成 |
| 真实确定性链路 | 回放真实 `DefaultIntentRoutingService`、`DrawioCanvasMcpService`、XML patch/merge、Analyzer 和 repair feedback | 完成 |
| repair 序列可观察 | `edit-add-worker` 执行两次真实 mutation，并分别断言存在 blocking issue、修复后无 blocking issue | 完成 |
| 三类确定性 Grader | trajectory policy、独立 XML integrity、visual quality 均输出独立 version/evidence | 完成 |
| XML hard gate | 检查可解析性、`mxGraphModel/root`、基础节点、唯一 id、vertex/edge/geometry、parent/source/target 引用和序列化回读 | 完成 |
| 磁盘 batch 与异构 case | 12 个 `core-v1` YAML 全部由 `EvalBatchRunner` 经真实 Mode B 执行，覆盖 answer/clarify/review/create/edit 及 architecture/flowchart/ER/sequence | 完成 |
| 历史 bug tracer bullet | `regression-edit-success-unchanged-canvas-001` 固定 `c49f0a68` 的已脱敏症状回放并断言 FAIL；独立 `ProductionLiveEvalAdapterTest` 驱动真实 adapter seam，断言候选从持久化 artifact 得到 PASS。它不声称在测试中启动旧 SHA | 完成 |
| 报告与运行 manifest | JSON/Markdown 包含 case/grader version、git SHA、execution profile、prompt/skill/tool-policy hash、状态与耗时 | 完成 |
| PR 默认门禁 | 无真实模型依赖，纳入普通 Maven test；坏 case 逐 case 记 `ERROR` 后继续 | 完成 |

### Phase 1 边界

- `taskOutcome` 仍是录制 trajectory 标签，不能单独证明真实用户任务成功；Mode C 必须从回复、mutation、artifact 和错误信号投影。
- 当前 repair loop 是录制多工具序列对生产确定性组件的重放；真实 LLM 依据 repair feedback 自主再规划属于 Phase 6。
- graph semantic assertion、语义身份 preservation、fixture migration 和完整多轮 canvas state 属于 Phase 4。
- 12 个 case 用于证明 harness 能运行异构数据和历史回归，不满足最终 250–350 case 的统计覆盖目标。

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=ModeBReplayExecutionFactoryTest,EvalBatchRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean test
```

当前结果：12 个磁盘 case 全部通过 Mode B batch；历史 artifact false-success case 额外验证 baseline FAIL → candidate PASS。

## Phase 2：Trace-to-Eval 人工入口

**状态：完成**

| 方案要求 | 实现证据 | 结果 |
| --- | --- | --- |
| run 详情人工入口 | 管理端 `Diagram trace` 页面提供 `Create Eval Candidate`，显示 candidate id/status 和人工重建提示 | 完成 |
| metadata-only 创建 | 前端只提交 run id；`TraceToEvalIntakeService` 只读取 run/step metadata，不调用 Debug Trace 或 LLM | 完成 |
| 管理员权限与 CSRF | 入口复用 admin authorization；前端 POST 使用统一 CSRF header | 完成 |
| candidate/review/lineage 持久化 | migration、MyBatis mapper、repository 和 service 已覆盖三层记录；candidate 允许短期 source run，review/lineage 不保存 payload | 完成 |
| 人工为唯一批准门槛 | 只有 `APPROVED` candidate 可记录 publication；未批准发布由状态机拒绝 | 完成 |
| 不可回链 lineage | `EvalCaseLineage` 无 source run/candidate 字段，并有反射测试；Git case 不写 candidate/promotion id | 完成 |
| 幂等人工创建 | 同一 `(source_run_id, manual_review)` 重复操作返回原 candidate；service 与数据库唯一键双层保护 | 完成 |
| 审计和错误隔离 | 创建、审核、发布的成功、拒绝和运行时错误均写审计；运行时错误不向 UI 泄漏数据库细节 | 完成 |
| review/publication 原子性 | 两个跨表状态迁移使用事务边界，避免 review/lineage 写入与 candidate 状态半成功 | 完成 |
| 统一 Eval Case 交接 | 人工合成 YAML 使用 Phase 1 的同一 `EvalCaseDefinition`/loader，经 batch 验证后才记录 lineage；服务端不自动写 Git | 完成 |

### Phase 2 边界

- P0 管理端只提供 run → candidate 的人工入口；候选列表、筛选、批量去重与完整状态迁移 UI 属于 Phase 3。
- P0 不读取 debug payload、不脱敏、不调用 LLM；受控内容访问和 LLM Draft 属于 Phase 5。
- 当前人工审核 API 将 `DETECTED` 直接变为 `APPROVED/REJECTED`；枚举已保留完整状态，Phase 3 接队列时再启用 TRIAGED/UNDER_REVIEW 等迁移。
- publication endpoint 只记录已由人工添加并经 Harness 验证的 case reference，不是自动 `CasePublisherAdapter`，也不会写工作区或 Git。

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=TraceToEvalIntakeServiceTest,AdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test --test-name-pattern='metadata-only Trace-to-Eval' tests/admin-diagram-trace-page.test.mjs
npm run lint
npm run build
```

当前聚焦结果：20 个后端测试通过；前端入口测试与 production build 通过；lint 为 0 error、8 个本阶段前已存在的 drawio page warning。

## Phase 3：确定性 Candidate Queue

**状态：完成**

| 方案要求 | 实现证据 | 结果 |
| --- | --- | --- |
| 异步确定性 Selector | `EvalCandidateSelectorJob` 按配置定时调用 selector，默认关闭且不进入用户请求链路 | 完成 |
| metadata-only 规则 | 只读取 run/step/LLM/tool status、routing/mutation event metadata 和 canvas hash snapshot | 完成 |
| 执行失败规则 | run、step、LLM、tool 的 FAILED/ERROR 合并为 execution candidate | 完成 |
| mutation 失败规则 | create/modify/optimize tool failure 形成 artifact evidence | 完成 |
| repair/质量规则 | retryCount 达预算形成 medium evidence；`NEEDS_REPAIR` 形成 high blocking-quality evidence | 完成 |
| canvas 无变化规则 | mutating route 且至少两个有序 snapshot 的首尾 hash 相同形成 artifact evidence | 完成 |
| 终态与幂等 | 跳过 RUNNING run；同一 `(source_run_id, failure_family)` 合并 rule/evidence，service 查询与 DB unique key 防重复 | 完成 |
| 队列查询性能 | status/risk/discovered_at 复合索引；API 支持 status/risk/limit/offset | 完成 |
| 管理端队列 | `Candidates` 页面展示 risk/family/rules/evidence、源 trace 链接，并提供筛选 | 完成 |
| 受约束状态迁移 | 支持 TRIAGED、NEEDS_MANUAL_RECONSTRUCTION、UNDER_REVIEW、REJECTED/EXPIRED/PURGED；通用接口禁止跳到 APPROVED/PUBLISHED | 完成 |
| 权限与审计 | 列表和迁移复用 admin authorization；迁移使用 CSRF；成功/拒绝/运行时错误均审计 | 完成 |
| Debug/LLM 隔离 | selector、job、queue API 和 UI 均不读取 Debug Trace、不调用 LLM | 完成 |

### Phase 3 边界

- 普通 telemetry 尚无 Analyzer 逐 issue 类型/count；当前只用 `NEEDS_REPAIR` 表示阻塞问题残留，不能精确归因 critical/major 类别。
- canvas no-change 只有在至少两个 snapshot 提供可比 hash 时判定；缺 hash 不推断失败，避免假阳性。
- Undo、评分、立即重试和人工大改尚未采集，按方案留到 Phase 8 的隐私审批后扩展信号。
- queue 不生成 Draft、不读取 payload；这些能力属于 Phase 5。

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=DeterministicCandidateSelectorServiceTest,TraceToEvalIntakeServiceTest,AdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test tests/admin-eval-candidates-page.test.mjs
npm run lint
npm run build
```

当前聚焦结果：25 个后端测试、1 个 Candidate Queue 页面测试通过；production build 通过；lint 为 0 error、8 个既有 warning。

## Phase 4：Graph、保留度与多轮 Evaluation

**状态：完成**

| 方案要求 | 实现证据 | 结果 |
| --- | --- | --- |
| 确定性 Graph normalizer | `DrawioGraphNormalizer` 将 XML 投影为 canonical node/edge set，不依赖 embedding 或 LLM | 完成 |
| 版本化 alias map | graph assertion 必须声明 `aliasMapVersion`；只允许 case 内显式 alias，未知/拼写近似不模糊通过 | 完成 |
| Graph assertion grader | 支持 required/forbidden nodes 与 required directed edges/optional label，输出独立 grader version/evidence | 完成 |
| 语义身份 preservation | protected node 按 canonical label 匹配，cell id 合法变化不会误报 deletion | 完成 |
| 多轮 schema | 固定 `input.turns[]` 与等长 `replay.turns[]`；每回合有 router reply、outcome、tool sequence 和 expected route/change | 完成 |
| canvas state 累积 | 每回合 before XML 必须等于上一回合 after XML；无 mutation 回合保持不变 | 完成 |
| 多轮真实确定性组件 | 每回合运行真实 router 后处理和真实 canvas tool/patch/analyzer，最终再执行 graph/preservation/visual | 完成 |
| fixture contract | 所有 case 固定 `fixture-v1` + `drawio-v1`；loader fail closed，迁移规则禁止运行时静默改写 | 完成 |
| 可审阅 diff 证据 | JSON/Markdown report 独立记录 semantic nodes added/removed，不把正向证据混入失败 evidence | 完成 |
| 磁盘闭环 | `clarify-then-edit` 两回合 case 经 batch 验证 clarify 不改图、edit 承接 canvas、Gateway 保留且 Worker 出现 | 完成 |

### Phase 4 边界

- canonical matching 是 exact normalized label + reviewed alias map；模糊候选只能进入 Judge/人工输入，绝不作为 CI hard pass。
- 当前 preservation hard gate 保护声明的节点语义身份；更细的 style/geometry/edge preservation 可按 case assertion 后续扩展。
- Mode B 使用录制 router reply，验证固定 turns 与 canvas state 传递；真实模型会话历史及自主澄清行为属于 Phase 6 Mode C。
- fixture migration 流程和兼容版本已固定；当前没有历史 contract 需要实际迁移，因此没有伪造空 migration 脚本。

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=DefaultEvalHarnessTest,ModeBReplayExecutionFactoryTest,EvalBatchRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean test
```

当前结果：12 个磁盘 case（含多轮与历史回归）全部通过 batch。

## Phase 5：受控 LLM 草拟

**状态：完成**

| 方案要求 | 实现证据 | 结果 |
| --- | --- | --- |
| 显式用途与权限 | 仅 admin endpoint 可触发，body 必须 `purposeConfirmed=true`，POST 使用 CSRF | 完成 |
| 受控 Debug 读取 | 通过现有 `AgentDebugTraceService.viewCapturesForRun` 读取，继承 TTL、scope 和读取审计 | 完成 |
| 脱敏先于模型 | `eval-sanitizer-v2` 在 model port 前清理 secret/Authorization/Cookie/email/phone/URL，并用单次 sanitize 共享 registry 将客户、公司、产品、服务、人员、账号、项目等实体稳定替换为无碰撞分类占位符 | 完成 |
| XML fail closed | capture 含 mxGraphModel/mxfile 时不调用模型，转 `NEEDS_MANUAL_RECONSTRUCTION` | 完成 |
| 专用模型边界 | `IEvalDraftModel` 可测试替换；生产 `ChatEvalDraftModel` 使用独立可配置 Draft Agent | 完成 |
| 严格 Draft schema | 只允许 failure summary/family、synthetic turns、fixture hint、route、assertions、confidence、human review 字段；未知字段拒绝 | 完成 |
| 输出泄漏防护 | 模型输出再次执行 secret/PII/XML/业务实体检查，并拒绝 run/user/candidate/debug capture ID；candidate evidence 与 debug capture 共用同一 sanitizer 边界 | 完成 |
| Draft 持久化 | `eval_case_draft` 只保存 schema-validated synthetic suggestion、sanitizer/model version 和 candidate reference | 完成 |
| 人工门槛 | 成功仅转 `DRAFT_READY` 且强制 `needsHumanReview=true`；UI 无 Approve/Publish draft 操作 | 完成 |
| 失败降级 | 无 capture、XML、模型异常或 schema 不合法均保留 candidate 并转人工重建，不覆盖为成功 | 完成 |
| 管理端入口 | TRIAGED card 显示 `Prepare draft`，二次确认后只展示摘要或人工重建原因 | 完成 |

### Phase 5 边界

- Draft 是建议，不是 `ApprovedEvalCase`；审核者仍需合成 fixture、校验断言并走 review/publication。
- sanitizer 对完整业务图选择拒绝而非“智能脱敏”，避免把真实架构长期写入 dataset。
- 未结构化文本若带有 confidential、trade-secret、raw production payload 等无法可靠匿名化的标记，同样 fail closed 转人工重建；确定性实体替换不是通用 NER 的安全承诺。
- Draft Agent id 默认 `300013`，部署必须配置对应 schema-only Agent；未配置/不可用会安全降级为人工重建。
- 本阶段不做 Judge，也不让 Draft model 参与 release gate。

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=TraceToEvalDraftServiceTest,TraceToEvalIntakeServiceTest,AdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test tests/admin-eval-candidates-page.test.mjs
npm run lint
npm run build
mvn clean test
```

当前聚焦结果：27 个后端测试通过；Candidate UI 测试、production build 通过；lint 0 error、8 个既有 warning。

## Phase 6：Live-model、Judge 与统计

**状态：平台实现完成；真实 baseline/校准数据待运营准备**

| 方案要求 | 实现证据 | 结果 |
| --- | --- | --- |
| production Mode C adapter | `ProductionLiveEvalAdapter` 运行真实 stream 链路；用隔离 `diagramId` 预置 fixture，完成后从 `ICanvasStateStore` 读取真实持久化画布，而不是把助手文本当 XML；episode 结束后软删除临时画布 | 完成 |
| 隔离运行 | 使用 `eval-system` 与独立 diagram/session/request/run id；bean 初始化不调用模型，PR/default test 零模型成本 | 完成 |
| 生产 execution profile | 记录 model credential、temperature、prompt/skill/tool-policy version、review iterations 和 token 单价 | 完成 |
| 重复 @1 采样 | `LiveEvalRunner` 每个 case 独立运行 N 次，不做 best-of-N | 完成 |
| ERROR 重跑 | 只有显式 `EvalInfrastructureException` 整 episode 最多重跑 2 次；FAIL 不重跑 | 完成 |
| ERROR/UNAVAILABLE 隔离 | ERROR、Judge 不可用不进入 TSR 分子/分母；有效 case/error rate 不足返回 NO_DECISION | 完成 |
| TSR@1 估计量 | 先计算每 case `p̂_i`，再对 case 等权求均值，符合产品 @1 与度量估计量区分 | 完成 |
| cluster bootstrap CI | 对 case-level success probability 重采样 2,000 次，输出确定性 95% CI | 完成 |
| 相对 gate 统计 | baseline/candidate 按 case 配对，只有 CI 上界越过负向阈值才 block | 完成 |
| 成本/时长模型 | 汇总 input/output tokens、execution-profile 单价、estimated cost、总 latency 与 availability | 完成 |
| 可观察 task outcome | mutation case 使用 canonical XML SHA-256 判断实际变化后才记为 `FULFILLED`；无最终持久化画布记为基础设施错误，不能用 run success 自证完成 | 完成 |
| Judge provider | text-only `ChatEvalJudge` 使用专用 Agent，只接受不含伪视觉分的固定 JSON（task/helpfulness/side-effect/severity/evidence）；Judge agent/model/temperature 与 prompt/rubric/input-projection/schema 全部进入 `judgeVersion`，被测 Agent 版本作为独立 evidence；Judge model version 未配置或 provider/schema 故障均安全 UNAVAILABLE | 完成 |
| Judge 输入证据 | 输入含 initial/final graph、deterministic issues、tool 摘要和被测 Agent 版本对象；当前 provider 是 text-only，因此所有 diagram case 无条件 UNAVAILABLE，不允许用字符串图片引用或 XML 文本臆测 visual score | 完成 |
| Judge 边界与校准 | `LiveEvalRunner` 拒绝所有 raw `IEvalJudge`；只有批准的 `CalibratedEvalJudge` 才能评分，版本不符仍强制 UNAVAILABLE | 完成 |
| JSON/Markdown 报告 | 输出 TSR、CI、样本数、error/availability、token/cost/latency | 完成 |

### 尚未伪造为“已完成”的运营前置

- 当前仓库只有 12 个 core case，尚未达到设计目标 250–350；统计服务会因 `minimumCases` 返回 `NO_DECISION`。
- 尚未收集 60–80 个双人标注 Judge 校准 case；生产 `ChatEvalJudge` 已实现，但在 `CalibratedEvalJudge` 获得批准前仍返回 `UNAVAILABLE`，不能进入 release score。
- diagram Judge 的 render/VLM adapter 尚未接入；未来必须以能真实接收 before/after 像素的 multimodal provider 替换 text-only `ChatEvalJudge`。在此之前图任务 Judge 无条件 `UNAVAILABLE`，当前可用范围仅是无需视觉证据的答问类 case。
- 部署必须把 `zipp.evaluation.judge-model-version` 固定为 Judge provider 的实际模型/快照标识；默认 `unconfigured` 会 fail closed，不能产生可校准评分。
- 默认 Mode C adapter 当前支持 `input.user` 单回合；多回合真实 session adapter 尚未接入时会成为 ERROR 并触发 NO_DECISION，不会回退为 Mode B 冒充。
- 本地/CI 未提供真实 provider credential，因此本阶段没有产生虚假的成本或 baseline 数值；部署时由 execution profile 提供 credential 与价格。

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=ProductionLiveEvalAdapterTest,ChatEvalJudgeTest,LiveEvalStatisticsTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean test
```

当前平台结果：6 个 live/statistics/Judge 测试通过；真实 Mode C 调用需显式凭据和受控运行环境。

## Phase 7：Release Gate 与封存集

**状态：代码实现完成；封存数据未挂载时强制 NO_DECISION**

| 方案要求 | 实现证据 | 结果 |
| --- | --- | --- |
| 三态 release 结论 | `EvalReleaseGateService` 只返回 PASS/BLOCK/NO_DECISION | 完成 |
| 确定性 hard gate | 任一明确 `FAIL` 立即 BLOCK，并列出 case/risk；不等待统计平均掩盖 | 完成 |
| 基础设施隔离 | ERROR/UNAVAILABLE 只导致 NO_DECISION，不误报 Agent regression | 完成 |
| 相对统计 gate | paired comparison 只有已 READY 且 `blocked=true` 才 BLOCK；CI 不足 NO_DECISION | 完成 |
| Judge calibration gate | required Judge 未校准或版本未批准时 NO_DECISION | 完成 |
| 外置封存集 | `SequesteredEvalCaseLoader` 只允许 repository 外受控路径，解析同一 ApprovedEvalCase schema | 完成 |
| 封存集最小量 | 少于批准数量时 NO_DECISION；默认要求 20，可由部署配置 | 完成 |
| 防泄漏报告 | release report 只写 outcome/reasons，不写封存 case 内容、fixture、prompt/XML | 完成 |
| JSON/Markdown gate artifact | `release-gate.json/md` 可供 CI/人工评审读取 | 完成 |

### Phase 7 当前外部状态

- `ZIPP_EVAL_SEQUESTERED_ROOT` 默认空，且必须指向产品仓库外的受控挂载；当前仓库不包含任何封存 case。
- 由于 Phase 6 的 case 数与 Judge 校准仍未达到运营门槛，当前真实 release run 应得到 `NO_DECISION`，不是 PASS。
- 本阶段提供可被 CI 调用的 gate/service/report；具体 CI/CD 平台命令需在部署仓库按其发布系统接线。

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=EvalReleaseGateServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean test
```

当前结果：5 个 Release Gate/封存 loader 测试通过。

## Phase 8：Canary、Case Health 与持续运营

**状态：代码实现完成；真实 canary/新增用户信号需部署与隐私审批**

| 方案要求 | 实现证据 | 结果 |
| --- | --- | --- |
| canary 三态决策 | `EvalCanaryService` 输出 CONTINUE/HALT_RECOMMENDED/NO_DECISION | 完成 |
| 安全信号优先 | canary 出现 critical finding 立即 HALT_RECOMMENDED，不被 infrastructure error 掩盖 | 完成 |
| 相对线上阈值 | 比较 failure-rate increase、P95 latency ratio、average cost ratio | 完成 |
| 基础设施隔离 | 请求量不足或 infrastructure error rate 超阈值返回 NO_DECISION | 完成 |
| 不越权部署 | service 只给 recommendation，不执行 rollout、rollback 或外部发布动作 | 完成 |
| case health | 按 eligible samples 标记 HEALTHY、FLAKY、ALWAYS_PASS_REVIEW、UNSCORABLE、STALE_REVIEW、BROKEN_BASELINE | 完成 |
| 非敏感反馈回写 | `eval_case_health` 只保存 case/version、baseline reproduced、health/summary/time，无 run/user/payload | 完成 |
| 运营报告 | 输出 `canary-decision.json` 与 `case-health.json`，时间显式 ISO 序列化 | 完成 |

### Phase 8 外部状态与授权边界

- 当前没有正在进行的真实 canary rollout，因此没有生成线上 CONTINUE/HALT 结论；服务等待部署平台传入窗口指标。
- Undo、低评分、立即重试和人工大改尚未获得本任务中的隐私审批，也没有相应前端事件契约，因此没有擅自新增采集。
- 若后续批准这些信号，应先完成事件目的说明、retention/deletion 契约和 telemetry schema，再作为 Candidate signal；不得直接当 TSR 或失败标签。
- case health 应由 nightly/RC 结果周期性运行；`ALWAYS_PASS_REVIEW` 是审查/去重提示，不代表自动删除 case。

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=EvalOperationsServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean test
```

当前结果：4 个 Canary/Case Health 测试通过。

## 后续顺序

代码阶段 0–8 已按顺序完成。进入真实工业运行前仍需完成 Phase 6/7 记录的运营前置：扩充 curated case、人工 Judge 校准、外置封存集、真实 provider baseline 和部署平台接线。

## Control Plane CP0：契约与边界

**状态：完成**

| 实施计划要求 | 实现证据 | 结果 |
| --- | --- | --- |
| Working Copy 复用统一 Case schema | `EvalCaseWorkingCopy` 直接承载现有 `EvalCaseDefinition`，没有第二套 UI/Trace Case | 完成 |
| 发布对象不可变 | `EvalCaseVersion`、`EvalDatasetVersion`、`EvalRun`、`EvalEpisode` 使用不可变值对象 | 完成 |
| 运行与 Gate 状态分离 | `EvalRunStatus` 与 `EvalGateOutcome` 独立，`COMPLETED` 不代表 `PASS` | 完成 |
| FAIL/ERROR/UNAVAILABLE 分离 | `EvalEpisodeStatus` 固定四态 | 完成 |
| 运行模式和 Dataset 分层 | `EvalRunMode` 固定 Mode B/Mode C/Release；`EvalDatasetClass` 固定 dev/core/sequestered | 完成 |
| 权限契约 | `EvalAdminRole` 固定 Editor/Reviewer/Admin/Release Owner | 完成 |
| 不可变存储 seam | `IEvalCaseArtifactStore` 使用 content hash；`IEvalDatasetStore` 固定版本查询 | 完成 |
| 稳定错误与审计 target | `EvalControlPlaneErrorCode` 和复用 `admin_audit_log` 的 `EvalControlPlaneAuditTypes` | 完成 |
| 隐私字段边界 | Published `EvalCaseVersion` 无 source run/candidate 字段，反射测试锁定 | 完成 |

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=EvalControlPlaneContractsTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean test
```

CP0 只冻结跨阶段契约，不实现持久化或 Admin API；这些内容从 CP1 开始按 [Control Plane 实施计划](2026-07-13-evaluation-control-plane-implementation-plan.md) 逐阶段交付。

## Control Plane CP1：Case Working Copy 后端

**状态：完成**

| 实施计划要求 | 实现证据 | 结果 |
| --- | --- | --- |
| Working Copy 持久化 | `eval_case_working_copy` migration、显式 MyBatis resultMap、repository 和 JSON round-trip | 完成 |
| 统一 Case schema | definition JSON 始终序列化现有 `EvalCaseDefinition` | 完成 |
| Manual / Import | `createManual`、`createImported` 生成 owner-scoped DRAFT | 完成 |
| Working Copy Clone | Clone 创建新 identity/revision，清除 candidate linkage | 完成 |
| Trace Draft 转换 | 读取最新 `EvalCaseDraft`，只投影 synthetic turns/route/sanitizer，不复制 source run/debug id | 完成 |
| 乐观锁 | update 使用 `(id, expected_revision)` 条件更新；冲突返回稳定 `REVISION_CONFLICT` | 完成 |
| 所有权与角色策略 | Editor 只能读写自己的 Draft；Admin/Release Owner 可跨 owner 管理 | 完成 |
| 独立 Admin API | 新建 `EvaluationAdminController`，不继续膨胀生产 Trace `AdminController` | 完成 |
| 权限、CSRF 与审计 | API 复用 admin authorization/全局写保护，操作写入现有 `admin_audit_log` target type | 完成 |
| 错误隔离 | Validation、Not Found、Revision、State、Forbidden、Infrastructure 使用稳定错误码 | 完成 |

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=EvalCaseWorkingCopyServiceTest,EvalCaseWorkingCopyRepositoryTest,EvaluationAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean test
```

CP1 不执行 schema/privacy/fixture Validate，也不批准或发布 Case；这些行为属于 CP2/CP3。

## Control Plane CP2：Case Studio、Validate 与单 Case Dry Run

**状态：完成**

| 实施计划要求 | 实现证据 | 结果 |
| --- | --- | --- |
| 确定性 Validation | `EvalCaseValidationService` 使用真实 `EvalCaseLoader` 检查 schema、fixture/XML contract version、graph alias version、privacy、replay 和禁止 provenance 字段 | 完成 |
| Case 自有 Mode B replay | `ModeBReplayExecutionFactory` 已移到 production domain；从每个 Working Copy 的 input/replay 构造真实 router/tool/XML 执行，不共享硬编码场景 | 完成 |
| Dry Run 证据 | `EvalCaseDryRunService` 返回 PASS/FAIL/ERROR、grader、trace、before/after XML，并写不可变 `eval_case_evidence` | 完成 |
| 状态机 | Working Copy 严格执行 VALIDATING、VALIDATED、DRY_RUNNING、DRY_RUN_PASSED/FAILED、UNDER_REVIEW、APPROVED/REJECTED | 完成 |
| 人工审核与 four-eyes | 审核记录独立持久化；高/critical risk 的创建者不能自批 | 完成 |
| Admin API 与审计 | validate、dry-runs、submit-review、approve、reject 均鉴权并复用 `admin_audit_log` | 完成 |
| Case Studio UI | Cases 列表、YAML Import、JSON form editor/YAML preview、Validation evidence、Mode B 结果、before/after、trace、审核动作 | 完成 |
| 多 route 回放 | 生命周期测试覆盖 answer_only、create_new、edit_existing，另由 core-v1 batch 覆盖 clarify/review/multi-turn | 完成 |
| 结果语义 | UI 明确区分 Validation FAIL、Eval FAIL 与 infrastructure ERROR/UNAVAILABLE | 完成 |

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=EvalCaseLifecycleServiceTest,EvalCaseLifecycleRepositoryTest,EvaluationAdminControllerTest,EvalBatchRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test tests/admin-eval-cases-page.test.mjs
npm run lint
npm run build
```

CP2 只把 Working Copy 推进到 `APPROVED`；不可变 Case Version 与 Dataset 发布属于 CP3。

## Control Plane CP3：不可变 Case 与 Dataset Version

**状态：完成**

| 实施计划要求 | 实现证据 | 结果 |
| --- | --- | --- |
| Case 不可变发布 | `EvalCasePublisherService` 只接受 APPROVED；canonical JSON + SHA-256 + `eval_case_version` 元数据 | 完成 |
| 幂等与冲突 | 同 Case/version/content 返回既有版本；同版本不同 content 拒绝 | 完成 |
| Artifact 不写 Git | `FileSystemEvalCaseArtifactStore` 写配置的仓库外 content-addressed root，校验 hash 和 root scope | 完成 |
| Published Case Clone/Retire | 从指定不可变 artifact 创建 `PUBLISHED_CASE_CLONE` Working Copy；Retire 只改 metadata | 完成 |
| Dataset Draft 与乐观锁 | `eval_dataset`、version/member 表；DRAFT member edit 使用 revision conflict | 完成 |
| 精确 version pin | 每个 member 固定 `(case_id, case_version)`，Validate 要求版本已发布且未 retired | 完成 |
| Dataset 冻结 | VALIDATED 才能 PUBLISHED；发布写 member hash、publisher/time，之后禁止 edit | 完成 |
| dev/core promotion | Clone Version 可将 dev 成员复制到目标 core Dataset 的新 DRAFT，不改源版本 | 完成 |
| 历史可重放 source | `EvalDatasetCaseSource` 从 Published Dataset 加载其精确 artifact；磁盘 core-v1 runner 保持不变 | 完成 |
| Sequestered 隔离 | 普通 Admin list/API 不返回 sequestered Dataset；Case source/coverage 要求 Release Owner | 完成 |
| Coverage | UI/API 报 route/risk/language/diagram type/agent 原始计数，不伪造 F1 | 完成 |
| Admin UI | Published Case history/clone/retire；Dataset member editor、diff、confirmation、coverage、promotion | 完成 |

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=EvalPublishingServiceTest,FileSystemEvalCaseArtifactStoreTest,EvalControlPlaneContractsTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test tests/admin-eval-cases-page.test.mjs tests/admin-eval-datasets-page.test.mjs
npm run lint
npm run build
```

部署必须显式配置 `zipp.evaluation.artifact-root`/`ZIPP_EVAL_ARTIFACT_ROOT` 到持久化、受控且不位于产品 Git checkout 的目录。Dataset 批量 Dry Run 在 CP4 由异步 Eval Run 编排统一提供，避免 CP3 建立第二条同步批跑链路。

## Control Plane CP4：Mode B Eval Run 编排与持久化

**状态：完成**

| 实施计划要求 | 实现证据 | 结果 |
| --- | --- | --- |
| 不可变 Run manifest | `EvalRun` 固定 mode/dataset/version/repetitions/git SHA/profile/grader manifest/idempotency key | 完成 |
| 异步 HTTP 边界 | `IEvalJobExecutor` + bounded application `ThreadPoolExecutor`；start API 只入队 | 完成 |
| Dataset Mode B | `EvalDatasetCaseSource` 加载精确 Published Dataset，`ModeBReplayExecutionFactory` 执行真实确定性链 | 完成 |
| Episode 隔离 | 每个 Case×repetition 独立 PASS/FAIL/ERROR/UNAVAILABLE；单 Case 异常不终止批次 | 完成 |
| 完整持久化 | `eval_run`、`eval_episode`、`eval_grader_result` + 显式 MyBatis resultMap/repository | 完成 |
| Artifact/report | normalized execution、canvas/trace 与最终 report 写受控仓库外 Run Artifact Store | 完成 |
| Cancel | QUEUED/RUNNING 可取消；worker 在 Episode 边界检查，模型中不存在悬挂 RUNNING Episode | 完成 |
| Retry ERROR only | retry 只选择 ERROR Episode 并增加 attempt；Agent FAIL 保持不变 | 完成 |
| 幂等 start | service lookup + DB unique key；并发插入回查 winner | 完成 |
| 12 个 core Case | 全部现有 core-v1 fixture 经新 orchestrator 产生 12 个独立 Episode | 完成 |
| Admin API 与审计 | start/list/get/episodes/cancel/retry-errors，成功/拒绝/异常复用 admin audit | 完成 |

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=EvalRunOrchestratorTest,EvalRunRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean test
```

`COMPLETED` 仅表示所有可执行 Episode 已完成，不表示质量 PASS；Gate outcome 仍独立。CP4 不调用真实模型，Mode C/Release 接线属于 CP6。

## Control Plane CP5：Eval Run 可视化

**状态：完成**

| 实施计划要求 | 实现证据 | 结果 |
| --- | --- | --- |
| Run 列表 | `/admin/eval-runs` 展示 mode、Dataset/version、执行状态、进度、PASS/FAIL/ERROR、总 latency/cost | 完成 |
| Run → Case → Episode → Evidence | Run Detail 的 Case Matrix、Episode Drawer 和受控 artifact endpoint 提供四层下钻 | 完成 |
| 专用 read model | `EvalRunQueryService` 输出 summary/matrix/detail/artifact projection，前端不拼 storage/domain 对象 | 完成 |
| Case Matrix | 每个 repetition/attempt 展示 agent、route、risk、language、status 和 grader status | 完成 |
| 筛选 | 后端和 UI 支持 FAIL/ERROR/UNAVAILABLE、agent、route、risk、language | 完成 |
| 可解释失败 | `blockingReason` 汇总失败 grader 或基础设施错误；FAIL 与 ERROR 使用不同统计、颜色和文案 | 完成 |
| 大对象隔离 | list/detail 不内联 XML/trace；管理员显式请求 Episode artifact 后才加载 canvas、trace 和 semantic diff | 完成 |
| Sequestered 边界 | artifact/detail 读取仍经过 Dataset source 权限校验；普通 Admin 不能读取 sequestered Case 内容 | 完成 |
| Baseline/Candidate | 只有 Run manifest 同时存在两个 reference 时才展示比较占位 | 完成 |
| Gate outcome 边界 | CP5 只展示执行状态和 Episode outcome；统计 Gate outcome 在 CP6 接入，避免把 COMPLETED 误称 PASS | 完成 |

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=EvalRunQueryServiceTest,EvalRunOrchestratorTest,EvalRunRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test tests/admin-eval-runs-page.test.mjs
npm run lint
npm run build
mvn clean test
```

CP5 完成后，Mode B 管理员闭环已可用于启动、观察、筛选和解释确定性 Dataset Run；真实模型重复采样、Judge、统计和 Release Gate 属于 CP6。

## Control Plane CP6：Mode C、统计与 Release Gate Operations

**状态：代码接线完成；真实凭据、校准集、封存集和 baseline 属于 CP7 运营前置**

| 实施计划要求 | 实现证据 | 结果 |
| --- | --- | --- |
| Mode C / Release 分派 | `EvalRunOrchestrator` 保持统一 Run 生命周期，`EvalLiveRunService` 封装 live sampling/statistics/Gate | 完成 |
| 真实生产 adapter | `ProductionEvalLiveRunSupport` 将既有 `ProductionLiveEvalAdapter`、`ChatEvalJudge` 和配置化 readiness 接入 | 完成 |
| repetition / retry | 复用 `LiveEvalRunner.runDetailed`；仅 `EvalInfrastructureException` 最多重试两次，Agent FAIL 不重跑 | 完成 |
| Episode evidence | `EvalSampleResult` 映射 Episode；确定性 Grader、Judge 和 execution artifact 分别持久化 | 完成 |
| Judge 持久化 | `eval_judge_result` 映射现有 `EvalJudgeResult`，保留 judge/calibration version、status、score/evidence | 完成 |
| 统计与 paired comparison | 复用 `EvalStatisticsService` 生成 TSR@1、bootstrap CI、availability、token/cost/latency 和 case-level paired delta | 完成 |
| Release Gate | 复用 `EvalReleaseGateService`；真实 Run Episode/report/readiness 生成 PASS/BLOCK/NO_DECISION 并落 `eval_gate_decision` | 完成 |
| Evidence 可追溯 | Gate 记录固定 run ID/version/reasons/time，Case hard failure reason 带 case/risk；报告与 Run `report_ref` 关联 | 完成 |
| Readiness / comparability | provider credential、Judge calibration、sequestered count 和 execution profile comparability 缺失时不产生虚假 PASS | 完成 |
| Budget / timeout | Run manifest 固定 cost budget；超预算 Episode 为 UNAVAILABLE；生产 adapter 沿用受控 live timeout | 完成 |
| 多轮边界 | 尚无 live session adapter 时逐 Episode 记录 `MultiTurnLiveAdapterUnsupported` ERROR，不冒充模型 FAIL | 完成 |
| Release Owner | `admin.release-owner-emails` 独立于普通 Admin；Release start/evaluate/override 均要求 Release Owner | 完成 |
| Override 安全 | 只允许带理由 override BLOCK；原始 outcome/reasons 不改写，NO_DECISION 不可 override，操作进入通用 Admin Audit | 完成 |
| Admin UI / CI API | New Run 支持三种 Mode、baseline/candidate/repetition/budget；详情展示 TSR/CI/readiness/delta/Gate/Judge，Gate API 可供 CI 调用 | 完成 |
| 隐私边界 | Live report 仅含聚合统计/readiness/Gate，不含 Case body 或 sequestered payload；大 execution 继续走受控 artifact endpoint | 完成 |

关键部署配置（均默认 fail closed）：

```text
zipp.evaluation.live-enabled=false
zipp.evaluation.live-timeout-ms=120000
zipp.evaluation.judge-calibration-approved=false
zipp.evaluation.judge-calibration-version=unconfigured
zipp.evaluation.judge-calibrated-version=unconfigured
zipp.evaluation.sequestered-case-count=0
zipp.evaluation.minimum-sequestered-cases=20
admin.release-owner-emails=
```

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=EvalLiveRunOrchestratorTest,EvalRunRepositoryTest,LiveEvalStatisticsTest,EvalReleaseGateServiceTest,EvalRunOrchestratorTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test tests/admin-eval-runs-page.test.mjs
npm run lint
npm run build
mvn clean test
```

CP6 不伪造运营 readiness：默认配置会让真实 Release Run 得到 UNAVAILABLE/NO_DECISION。扩充 Dataset、校准 Judge、挂载外置封存集和建立真实 baseline 在 CP7 完成。

## Control Plane CP7：真实运营数据准备

**状态：等待真实数据与外部运营输入，未伪造完成**

- 当前仓库只有 12 个 core-v1 Case，尚未达到 250–350 的发布级覆盖规模。
- Text Judge 和 Semantic Miner 均缺少 50–80 条双人/人工标注校准集及批准记录。
- 外置 Sequestered Dataset、真实 provider 凭据、首个 Mode C baseline、实际 token 单价和 CI/nightly 调度尚未提供。
- CP6/CP8 因此继续默认 fail closed；缺少这些证据时分别返回 `NO_DECISION` 或 `UNAVAILABLE`，不会用 synthetic 标签冒充运营 readiness。

## Control Plane CP8：LLM Semantic Anomaly Miner

**状态：平台代码完成；真实校准数据和批准仍属于运营前置**

| 实施计划要求 | 实现证据 | 结果 |
| --- | --- | --- |
| 独立模型 port | `ISemanticAnomalyMiner` 与 `ChatSemanticAnomalyMiner` 独立于 Draft Agent | 完成 |
| 脱敏 Trace Projection | `SemanticTraceProjector` 去除 run/user/request/diagram/span ID，不传 trace metadata JSON；复用 fail-closed sanitizer | 完成 |
| Strict schema/version | 固定六字段 JSON、枚举/类型/production ID 校验；version 同时 pin model/prompt/schema | 完成 |
| 定向/随机抽样 | `TARGETED`、`RANDOM`、`MIXED`；只扫描终态 run，单次样本数受配置上限约束 | 完成 |
| 异步与故障隔离 | Admin start 只进入 bounded Eval job；单样本 provider/schema/privacy 错误不终止批次或生产 Agent | 完成 |
| 硬超时与资源隔离 | `SemanticMinerCallExecutor` 使用独立有界线程池、Future deadline 和 cancel | 完成 |
| Candidate provenance/dedupe | Candidate 记录 `MODEL_DETECTED`、model version/confidence/evidence；沿 `(source_run_id, failure_family)` 合并 | 完成 |
| 成本和运行可观测性 | `eval_semantic_miner_run` 持久化 sampled/analyzed/candidate/error/cost/status/readiness | 完成 |
| 低置信保护 | 低于 0.5 不入队；0.5–0.8 即便模型建议 high/critical 也降为 medium | 完成 |
| Admin 操作面 | Candidate 页面可启动 Targeted/Random/Mixed 扫描、查看作业状态和 model evidence | 完成 |
| 权限/审计/工作流边界 | 启动和列表要求 Admin、复用 `admin_audit_log`；Miner 无 Approve/Publish/Gate 接口 | 完成 |

关键部署配置（均默认 fail closed）：

```text
zipp.evaluation.semantic-miner-enabled=false
zipp.evaluation.semantic-miner-calibration-approved=false
zipp.evaluation.semantic-miner-model-version=unconfigured
zipp.evaluation.semantic-miner-calibrated-version=unconfigured
zipp.evaluation.semantic-miner-max-samples=50
zipp.evaluation.semantic-miner-timeout-ms=30000
zipp.evaluation.semantic-miner-call-threads=2
zipp.evaluation.semantic-miner-estimated-cost-per-analysis-usd=0
```

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=SemanticAnomalyDiscoveryServiceTest,ChatSemanticAnomalyMinerTest,SemanticMinerCallExecutorTest,SemanticMinerRepositoryTest,SemanticMinerAdminControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test tests/admin-eval-candidates-page.test.mjs
npm run lint
npm run build
```

验收中的 `SUCCESS + 无法加载`、输入/输出 identifier 泄漏、模型超时隔离、持久化 round-trip 和 Admin fail-closed 均已有自动化测试。尚未完成的是用真实 50–80 条标注 Trace 计算 precision/recall/Macro-F1、人工接受率和单有效 Candidate 成本；在该校准版本获批前，生产配置无法启动 Miner。
