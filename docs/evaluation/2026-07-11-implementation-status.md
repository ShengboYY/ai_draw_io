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
| 磁盘 batch 与异构 case | 10 个 `core-v1` YAML 全部由 `EvalBatchRunner` 经真实 Mode B 执行，覆盖 answer/clarify/review/create/edit 及 architecture/flowchart/ER/sequence | 完成 |
| 报告与运行 manifest | JSON/Markdown 包含 case/grader version、git SHA、execution profile、prompt/skill/tool-policy hash、状态与耗时 | 完成 |
| PR 默认门禁 | 无真实模型依赖，纳入普通 Maven test；坏 case 逐 case 记 `ERROR` 后继续 | 完成 |

### Phase 1 边界

- `taskOutcome` 仍是录制 trajectory 标签，不能单独证明真实用户任务成功；Mode C 必须从回复、mutation、artifact 和错误信号投影。
- 当前 repair loop 是录制多工具序列对生产确定性组件的重放；真实 LLM 依据 repair feedback 自主再规划属于 Phase 6。
- graph semantic assertion、语义身份 preservation、fixture migration 和完整多轮 canvas state 属于 Phase 4。
- 10 个 case 用于证明 harness 能运行异构数据，不满足最终 250–350 case 的统计覆盖目标。

验证命令：

```text
mvn -pl ai-agent-draw-io-app -am -Dtest=ModeBReplayExecutionFactoryTest,EvalBatchRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean test
```

当前结果：13 个 evaluation 测试、2 个 infrastructure 测试通过；10 个磁盘 case 全部通过 Mode B batch。

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

## 后续顺序

1. 实现 Phase 4：Graph、保留度和多轮 Evaluation；
2. 实现 Phase 5：受控 LLM 草拟；
3. 实现 Phase 6：Live-model、Judge 与统计；
4. 实现 Phase 7：Release Gate 与封存集；
5. 实现 Phase 8：Canary、case health 和持续运营。
