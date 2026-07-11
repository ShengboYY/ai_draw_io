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

## 后续顺序

1. 审查并收口 Phase 2：Trace-to-Eval 人工入口；
2. 实现 Phase 3：确定性 Candidate Queue；
3. 实现 Phase 4：Graph、保留度和多轮 Evaluation；
4. 实现 Phase 5：受控 LLM 草拟；
5. 实现 Phase 6：Live-model、Judge 与统计；
6. 实现 Phase 7：Release Gate 与封存集；
7. 实现 Phase 8：Canary、case health 和持续运营。
