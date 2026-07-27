# Chartbook Context、Memory 与绘图来源链路 Wayfinder

目标：让 Chartbook 具备类似 Project 的稳定上下文，并让普通画图完全独立于资料基础设施。

Direct、Retrieval 与 Evidence Answer 必须可复现、可解释、可降级；受治理的 Memory 最后建立在统一 Context 合同上。

规范优先级：本 Wayfinder 负责交付顺序，[完整目标架构](./2026-07-25-context-memory-source-decoupled-architecture.md)负责目标合同。

对 turn 启动顺序、source demand、requiredness、fallback 与 snapshot 时点，2026-07-25 目标合同取代旧设计。

M0 正式 ADR 完成前，旧文档只继续提供 owner/scope、lifecycle/retention、citation persistence 与 target resolution 约束。

## Notes

- Domain: AI-assisted diagramming, grounded retrieval, project context, memory.
- Standing decisions:
  - 产品术语继续使用 **Chartbook**，不改名为 Project。
  - Conversation、Diagram、Chartbook 决定“哪些资料可访问”；不等于本轮必须使用。
  - Project AUTO 包含 Conversation、Diagram 与 Chartbook；永不包含 Personal Library。当前消息文件持久化后已属于 Conversation Files。
  - 不恢复逐消息 checkbox 或 Direct/Retrieval 技术模式选择器。
  - 不提供 source action 按钮。restricted-input Demand Interpreter 提出多语言 proposal；Resolver 校验 evidence、binding、referent 与 pinned policy。
  - 普通任务可以提出 Optional Source Discovery；只有相关性 Probe 命中后才进入 Retrieval。
  - composer `+` 上传只创建 Conversation File；随消息发送后才写 durable message-attachment binding。
  - Source snapshot、Owner/scope 授权、exact revision、read lease、citation guard 和原子提交不能被 Context 或 Memory 绕过。
  - Memory v1 只保存用户明确确认的工作决策，并且只能作为 `CONTEXT_ONLY`；术语、样式和长期约束只归 Chartbook Profile。
  - 当前请求高于 Chartbook instructions，Chartbook instructions 高于 Memory；明确冲突必须暴露，不能静默覆盖。
  - 迁移期对每个 unseen turn 先持久化 sticky legacy/V2 engine 与 policy。engine selection 不读取 prompt、语言、附件或 source demand。
  - 每个 V2 turn 在 Router/LLM 前原子 claim，并用 attempt epoch fencing；首次 claim 同时保存唯一 user message、附件绑定与固定执行策略。
  - transport disconnect 只 detach，服务端继续执行；显式取消使用独立 API，并与最终 commit 做持久化 CAS。
  - v1 不恢复历史进度流；已运行的同 turn 返回 `202 + status endpoint`，终态按保存结果重放。
  - 当前采用 single-active-instance 部署；数据库 singleton lock 是简化 migration control 的前提。
- Missing helpers: `/grilling` 与 `/domain-modeling` 当前不可用；涉及产品取舍的票据需由普通对话逐项确认。

## baseline: Current Implementation Baseline

Blocked by: none
Status: resolved
Type: Research

### Question

当前 Context、Memory 和资料执行链已经完成到什么程度？

### Answer

- Canvas XML、summary、analysis、version/hash 已服务端持久化并按轮恢复，是最可靠的 Context。
- Diagram 消息已持久化并可在 UI 恢复；最近可见消息进入 Router，但普通 Drawer 仍主要依赖 `InMemoryRunner`，后端重启后不会从数据库重建模型历史。
- Chartbook 已实现 diagram membership、shared files 和 owner-fenced source scope，但没有 instructions、goal、summary、glossary、default style 或跨图 decisions。
- 请求级 source snapshot、Evidence、citation 和 grounded commit 已实现；这属于授权/可复现性，不是 Memory。
- 当前每轮先以 `AUTO` 解析来源再路由；`sourceUse + evidenceNeed` 不是完整状态机。最近的故障隔离只修复了 snapshot 失败误伤普通画图，没有消除前置依赖。
- 当前没有持久 `turn_execution` claim、attempt fencing、租约接管、独立 cancel API 或可查询的 terminal outcome；进程内 handle 不能支持可靠重试。
- 生产代码中没有长期 Memory 的 extraction、store、consolidation、冲突、过期、编辑、删除或禁用机制。

关键依据：

- [Source Chain Redesign](./2026-07-24-chartbook-files-source-chain-redesign-plan.md)
- [ADR 0012](../adr/0012-persist-chartbook-evidence-and-keep-conversation-evidence-temporary.md)
- [ADR 0007](../adr/0007-separate-intent-routing-from-diagram-target-resolution.md)

## legacy-plain-containment: Restore Ordinary Drawing First

Blocked by: baseline
Status: resolved
Type: Task

### Question

在 M1–M6 完成前，如何先阻止现有 AUTO source freeze 继续误伤普通画图？

### Answer

H0 已完成：sync/stream 都先运行现有 Router，再按现有 source-use/evidence decision 调用 resolve/freeze。

普通 CREATE/EDIT/LAYOUT 对 Source Snapshot、Material、Pinecone 和 Evidence 的调用数必须为零。existing source-aware path 继续使用当前 owner fence、snapshot 和 grounded commit。

legacy optional AUTO enrichment 失败时可带 receipt 回退 Plain；existing Required/Evidence Answer 仍 fail closed。

本票不新增 source 按钮、关键词 parser、V2 claim/checkpoint 或长期 abstraction。M6 all-path cutover 后删除该 bridge。

H0 不实现“登录流程是否有相关项目文档”的自动发现；该能力由 M4 typed planning 完成，并随 M6 all-path canary 上线。

测试直接复现“画 sequence diagram 却因 source freeze 失败”的问题，并覆盖 sync/stream、普通零 source 调用和 existing Required failure。

## source-policy-adr: Freeze The Turn Context Policy

Blocked by: baseline
Status: resolved
Type: Grilling

### Question

如何用一份无歧义的合同区分普通画图、Direct、可选/必需 Retrieval、Direct + Retrieval 和 Evidence Answer？

### Answer

已由[完整目标架构](./2026-07-25-context-memory-source-decoupled-architecture.md)冻结：

- atomic claim + message attachment binding → Semantic Router || restricted-input Demand Interpreter → Resolver → Pre-Planner → conditional Probe → typed Planner；
- Demand Interpreter proposal 必须携带 current-instruction evidence span；Resolver 校验 digest、referent、model/policy version 与 confidence threshold；
- 两个模型 port 可并行；额外的小模型调用不读取资料，换取 Profile/Memory/history 无法污染 source demand；
- Required 只在语义清晰且 referent 可验证时成立；任一模型 port 非法或 unavailable 都返回 typed unavailable；
- 普通任务可产生 Optional Discovery query；资料 availability 本身不等于 demand，相关性由 Probe 判断；
- 弱指代或无法唯一解释时进入 clarification，不从 Profile、Memory、历史话术或“存在资料”补证据；
- `SourceFreePlan` 与 `SourceAwarePlan` 分型，普通画图 source 零调用；
- 当前消息附件是 Direct/exact Retrieval current-attachment referent 的唯一 authority；
- 用户用自然语言回复 clarification；hidden id 只负责绑定 durable clarification row；
- Retrieval 的 Optional fallback 是 Planner-signed branch，Required fail closed；
- Exact 与 Project AUTO 不合并；Evidence Answer strict grounding；
- snapshot 只在 source-aware plan 后冻结，sync/stream 最终共享同一 Outcome。

## context-envelope: Define One Server-Owned Turn Context

Blocked by: baseline
Status: resolved
Type: Prototype

### Question

Router、Drawer、Evidence Answer 和后续 Memory 应共享怎样的服务端权威 Context Envelope？

### Answer

已采用 server-owned `BaseTurnContext`：

- 只含 current request、trusted Canvas/selection、durable Conversation、membership、Profile 和 confirmed Memory；
- Base Context 不提供 source availability；两个模型 port 只知道当前 Chartbook membership，不知道文件数量或相关性；
- Source Snapshot/Evidence 在 plan 后单独绑定，绝不塞入 Base Context；
- Context read 使用 sealed valid states，consumer visibility/token budget 由 typed renderer 管理；
- ADK session 只是 cache，durable Conversation 是历史事实源；
- 前端 history/canvas 只做兼容声明，不能覆盖服务端权威。

## memory-policy: Decide What FreeDraw Is Allowed To Remember

Blocked by: baseline
Status: resolved
Type: Grilling

### Question

Memory v1 应记住什么、属于谁、保存多久，以及用户如何控制？

### Answer

已决定：

- Profile 独占 instructions、goal、summary、glossary、default style 和 stable constraints；
- Memory v1 只做同 Chartbook、explicit、confirmed、带 decision key/适用阶段的 working decisions；
- 外部事实、source ref、summary、Canvas 和敏感凭证禁止进入 Memory；
- recall 只读，冲突条目不注入；查看、确认、编辑、删除、禁用与 owner fence 独立管理；
- 自动 extraction 延后并先 shadow，永不自动 confirmed；
- Memory 永远不能支持 citation、触发 Retrieval 或扩大 source scope。

## turn-lifecycle-policy: Freeze Turn Claim, Recovery And Delivery Semantics

Blocked by: baseline
Status: resolved
Type: Grilling

### Question

同一 turn 的并发重试、进程崩溃、断流、取消和动态 flag 变化应如何线性化？

### Answer

已决定：

- 在 Router/LLM 前先固定 engine/policy assignment；只有 V2 admission 才原子 claim `turn_execution`，并保存唯一 user message 与附件绑定。
- claim 返回 terminal replay、already running、idempotency conflict 或带 attempt epoch 的 execution claim；只有最后一种可以调用模型。
- RUNNING attempt 持有有期租约并 heartbeat。租约过期后才能接管，接管递增 epoch；旧 epoch 的 heartbeat、attempt-scoped cancel、fallback 和 terminal commit 全部拒绝。
- handle 完成值分成 `PersistedTerminal`、`AttemptOwnershipLost` 与 `AttemptSelfAborted`；后两者只结束当前 delivery/转 status，不得伪造产品终态。
- 所有 strong commit 返回 fenced result：本 attempt 持久化、已有 terminal，或 fence lost。takeover 后仍 RUNNING 的 fence lost 不能假定已有 CAS winner。
- disconnect、writer failure 与 serialization failure 只 detach subscriber。显式 cancel/deadline 与最终 commit 对同一行做 CAS，数据库先成功者决定唯一终态。
- v1 对 RUNNING 返回 `202 + status endpoint`，不承诺事件重放。进程内 `TurnHandle` 不作为恢复合同。
- live flags 只决定首次 assignment；retry、restart 与 takeover 使用首次保存的 engine/policy、input binding 和 decision checkpoint。
- clarification/rejection 使用 fenced terminal-only commit；无 Canvas 的成功 answer/review 使用 response commit。V2 不允许 mutation 已提交但 terminal 待补偿。

## application-boundary: Place Turn Orchestration In A Real Module

Blocked by: baseline
Status: resolved
Type: Prototype

### Question

Turn application layer 应放在现有 HTTP trigger、bootstrap app，还是独立 Maven module？

### Answer

M1 新建 `ai-agent-draw-io-application`，保持模块化单体：

- application 放 Facade、Orchestrator、typed plans、handlers 与 application-owned ports，只依赖 domain/types；
- `trigger.http.turn` 只负责 HTTP/NDJSON 映射，并由 ArchUnit 限制为 api/application contract；
- infrastructure 实现 application/domain ports；`ai-agent-draw-io-app` 只负责 bootstrap/composition；
- 现有整个 trigger module 在 M1 仍因 legacy/其他 controllers 依赖 api/domain/types/application；Maven Enforcer 先锁 application 的反向依赖和无环图。

## formal-contract-adr: Publish The Superseding Contract

Blocked by: source-policy-adr, turn-lifecycle-policy, context-envelope, application-boundary
Status: resolved
Type: Task

### Question

何时把 2026-07-25 目标合同变成实现前必须遵守的正式 ADR？

### Answer

已合并 [ADR 0013：冻结 Turn Context、资料需求与执行合同](../adr/0013-freeze-turn-context-source-execution-contract.md)，冻结 engine assignment、turn claim/fence、disconnect/cancel、typed source demand、Plain 零 source、snapshot 后置、requiredness/fallback 与模块边界。

M1/M2 实现票据必须依赖该 ADR；旧文档清理可以随后并行进行。

## conversation-identity-foundation: Canonicalize Turn Scope Before M1

Blocked by: formal-contract-adr
Status: resolved
Type: Task

### Question

如何保证 M2 前同一个 turn 不会因 legacy session alias 与新 conversation id 形成两个幂等 key？

### Answer

已完成 durable canonical conversation id、active actor/conversation/diagram binding 与 immutable legacy alias mapping。`MySqlConversationCatalogAdapter` 对 default/canonical/legacy reference 都执行 owner + diagram + active 状态校验；Facade 在 fingerprint、assignment 和 claim 前解析 canonical `ConversationRef`，所有 M1 TurnKey/assignment/execution/clarification/tombstone 只保存 canonical id。

V2 HTTP control 与 submission adapter 已复用同一 `ConversationReferenceResolver`；legacy `ChatRequestDTO.sessionId` 被强制放入 `legacy:` alias namespace，即使 opaque session value 带有 `conversation:` 保留前缀也不能伪装 canonical id。canonical id 与 legacy alias resolve 到同一 `ConversationRef` 后，retry/status/cancel 使用同一 `TurnKey`；无法唯一解析时 fail closed。

现有 M1 migration 已建立 `conversation` 与 `conversation_legacy_alias` durable boundary，但不回填旧消息或重写 source/files/history scope；历史 scope 双读、consumer 迁移和 alias backfill horizon 明确留给 M3 `conversation-scope-migration`，不得在本票中把 runtime session 当长期 authority。

application/HTTP identity contract 与 application composition 回归通过；本票本轮没有新增 schema 或 migration。

## turn-execution-control: Implement Claim, Fence, Status And Cancellation

Blocked by: conversation-identity-foundation
Status: resolved
Type: Prototype

### Question

如何把已冻结的 turn lifecycle 合同实现成所有 V2 path 的共同前置条件？

### Answer

M1 控制面已按以下合同分批落地；生产 HTTP 仍未切入 V2，Plain/Response strong commit 留给 M2：

- `turn_engine_assignment`：sticky engine、fingerprint、canonical policy 与 hash；
- assignment/execution 还要固定 deterministic `MemoryWriteDeclaration` 的 schema、rule 与 semantic digest；模型输出不能在执行中补出 remember 意图；
- M1 创建 migration singleton row；启动先取得 single-active-instance lock，未取得锁不得开放 HTTP admission；
- `assignOrReuse` 在同一事务锁 migration row、查 existing、重验 generation、选择 stable cohort 并插入；
- `turn_execution`：state、attempt id/epoch、lease、context high-water、pinned policy、input binding、versioned terminal payload、cancel metadata；
- `conversation_message_attachment`：message 与 owner-fenced Conversation File 的 durable binding；
- `TurnEngineAdmissionService` 与强一致 `TurnStartCommitPort`：V2 claim + unique user message + attachment bindings；
- versioned fingerprint candidate set；existing 按保存 schema 比较，unseen 才使用 current schema；
- `currentTurnAttachments` 与 hidden clarification id 的 canonical schema/fingerprint binding；
- `TerminalOnlyTurnCommitPort` 与 `ResponseTurnCommitPort`；Plain strong atomic adapter 是 M2 接流 gate；
- lease/heartbeat/takeover 与所有 terminal commit 的 fence 条件更新；
- claim 只接收 lease policy/TTL，首个 lease与 heartbeat deadline 使用数据库时钟，并携带调用前 monotonic anchor；
- attempt-level completion 与 fenced commit outcome；ownership lost/self-abort 的 sync/stream 映射不得产生 final outcome；
- lease-safety interruption 必须 disable-and-drain write gate；generic cancellation/error path 不能提交业务终态；
- start/heartbeat/cancel/status/commit 复用 shared terminal decoder，schema unavailable 返回 typed unavailable；
- 独立 status、explicit cancel 与 fenced attempt-deadline ports；旧 epoch deadline timer 无权取消 takeover；
- disconnect detach、explicit cancel、deadline 和 commit race 的并发测试；
- 同 turn 并发提交、崩溃接管、stale attempt、flag flip、terminal replay 与 fingerprint conflict 测试。
- 同 TurnKey 的 memory declaration mismatch 必须 conflict；retry/takeover 始终使用首次固定的 declaration。
- fingerprint 纯函数已覆盖 opaque ref 顺序、alias/runtime session 排除和 memory declaration；文件存在性与 owner fence 在 claim 事务验证，只有 `SourcePlanningRequired` 才允许后续 Source Probe。

本切片修复 M1 CAS loser 的数据库读取语义：`MySqlTurnLifecycleAdapter` 的 explicit cancel、deadline cancel、heartbeat，以及 `MySqlTerminalOnlyTurnCommitAdapter` 在写入 CAS 失败后，均在事务内使用 `SELECT ... FOR UPDATE` current read 再判断 terminal/fence outcome，避免 MySQL `REPEATABLE READ` 的旧快照把并发 terminal winner 误报为 `FenceLost`。新增 adapter contract tests 覆盖 terminal replay reload；本切片没有新增 migration。

本切片继续收口 terminal envelope：新增 application-owned `TerminalOutcomeDecoder`，统一按 `terminal_payload_schema_version=1` 解码；status、start、heartbeat、explicit/deadline cancel、takeover，以及 Plain/terminal-only commit 的 terminal replay，在未知或缺失 schema 时返回 typed `TerminalUnavailable`，不再因 terminal payload 不完整抛异常或误报 fence。现有 `turn_execution.terminal_payload_schema_version` 已足够承载该合同，本切片没有新增或执行 migration。Legacy HTTP/production assignment 仍未切入 V2。

随后补齐 application handoff contract：`DefaultDiagramTurnFacade` 将 `TurnStartOutcome.TerminalUnavailable` 原样映射为 `TurnSubmission.TerminalUnavailable`，并以 contract test 固定 TurnKey、status 与 unavailable code 不丢失；本补充仍没有新增 migration。

本切片补齐 atomic claim 的 infrastructure evidence：`MySqlTurnStartCommitAdapter` 收窄为 `JdbcOperations` port seam，并新增 supported-schema terminal replay 与 unknown-schema typed unavailable contract tests；与既有 lifecycle/Plain/terminal-only adapter tests 合计 11 项定向测试通过。本切片没有新增或执行 migration。

本切片继续补齐 sticky assignment evidence：`MySqlTurnEngineAssignmentAdapter` 收窄为 `JdbcOperations` port seam，并新增 persisted assignment reuse 与同 fingerprint 下 `MEMORY_DECLARATION_CONFLICT` 的 infrastructure contract tests；定向 assignment/start/lifecycle/Plain/terminal-only 测试共 13 项通过。本切片没有新增或执行 migration。

本切片补齐 migration singleton 的 durable evidence：`MySqlTurnEngineMigrationControlAdapter` 收窄为 `JdbcOperations` port seam，并覆盖成功 compare-and-switch 返回新 generation、durable mode 已变化和 update CAS 丢失三种结果；assignment/start/lifecycle/migration/Plain/terminal-only 定向 infrastructure tests 共 16 项通过。本切片没有新增或执行 migration。

本切片收口 single-active-instance lock 的连接生命周期：`MySqlSingleActiveInstanceLock` 在 `GET_LOCK` 返回失败或抛出 SQL 异常时统一关闭未保留连接，并保持同一 boot 重入、竞争 boot 拒绝与 `RELEASE_LOCK` 释放语义；新增 3 项 infrastructure lock contract tests，连同 21 项 application M1 admission/control tests、19 项 infrastructure lifecycle/commit/lock tests 与 bootstrap composition test 均通过。本切片没有新增或执行 migration。

本切片把 migration pause window 接到可执行的 legacy preparation：`LegacyRetryExpiryPort` 先按 `created_at + 7 天` 的数据库时间补齐历史 LEGACY retry policy/horizon/state，再循环运行 due assignment → tombstone → `EXPIRED_GONE` scanner，所有批次完成后才调用 singleton mode switch；backfill/scan 异常不会切 mode 且 admission 由 `finally` 恢复。新增 coordinator 5 项、migration adapter 5 项、bootstrap composition 1 项与 migration contract 1 项验证；现有 M1 schema 已包含所需字段，本切片没有新增或执行 migration。

本切片将 mode switch 收窄为 `MigrationModeSwitchCommand(expectedGeneration, expectedMode, targetMode)`：durable adapter 在锁定 singleton row 后校验 generation、mode 与合法迁移图，只允许 `LEGACY → V2_CANARY → ALL_V2 → RETIRED`，并允许 `V2_CANARY → LEGACY` 回退；新增 stale-generation、非法跳转及有效 canary/all-v2/retired transition contract tests。现有 `generation`/`mode` 列已足够，本切片没有新增或执行 migration。

本切片把 owner fencing 前移到 `DefaultTurnControlFacade`：跨 owner 的 status 在调用 durable port 前返回 `TURN_NOT_FOUND`，跨 owner 的 cancel 在调用前返回 `OWNER_MISMATCH`；新增 2 项 application contract tests，application、infrastructure 与 bootstrap composition 定向回归均通过。本切片没有新增或执行 migration。

本切片修复 admission drain race：`TurnEngineAdmissionService.admit` 现在用 `tryEnter/leave` 包住独立调用，Facade 在更早解析 canonical conversation 前取得 scope，并通过 `admitAfterEntry` 继续已进入 drain 的 durable admission；migration pause 关闭新请求时，不会误拒绝已经跨过 barrier 的 turn。新增端到端 application contract test，application 全量、infrastructure M1 与 bootstrap composition 串行回归均通过。本切片没有新增或执行 migration。

本切片把 takeover 纳入 readiness fence：`DefaultTurnControlFacade` 只有在 authenticated owner 通过且本地 admission barrier 可进入时，才调用 durable takeover port；startup repair、migration pause 或 singleton lock 丢失期间返回 `TURN_INSTANCE_NOT_READY`，并保证 zero port call。heartbeat、deadline cancel 与 explicit cancel 仍不重新申请 admission scope，以便已进入执行的 turn 安全 drain。新增 closed-gate contract test，application、infrastructure 与 bootstrap composition 串行回归通过。本切片没有新增或执行 migration。

本切片收口 startup lock fencing：`TurnAdmissionGate` 在 orphan reconciliation 返回后再次确认当前 boot 仍持有 singleton lock；reconciliation 期间丢锁时返回 `InstanceLockOutcome.LOST` 并保持 admission closed，不会短暂开放 HTTP。新增 startup lock-loss contract test，application、infrastructure 与 bootstrap composition 串行回归通过。本切片没有新增或执行 migration。

本切片继续收口 startup failure fencing：`TurnAdmissionGate` 在锁获取失败或 orphan reconciliation 抛错时清除旧 `bootId`，因此失败重启后的 `resume()` 不会复用旧 boot 重新开放 admission。新增 failed-restart 与 reconciliation-failure contract tests；application 全量、infrastructure lifecycle/commit/lock 与 bootstrap composition 串行回归通过。本切片没有新增或执行 migration。

本切片收口 migration pause 的本地并发边界：`TurnEngineMigrationCoordinator` 的 mode switch 与 expiry scanner 共享 coordinator monitor 串行执行，后一个操作必须等待前一个完成 `resume()`，避免两个 operator 调用交错导致 admission 在仍有 migration 工作时提前开放。新增双线程 coordinator contract test；application 全量、infrastructure lifecycle/commit/lock 与 bootstrap composition 串行回归通过。本切片没有新增或执行 migration。

本切片补齐 migration pause 的持锁前置条件：`TurnAdmissionGate.pauseAndDrain()` 在关闭 admission 前验证当前 boot 仍 ready 且持有 singleton lock；锁丢失或 startup 尚未完成时返回 `TURN_INSTANCE_NOT_READY`，不会让 coordinator 继续执行 backfill/mode switch。新增 lock-loss pause contract test；application 全量、infrastructure lifecycle/commit/lock 与 bootstrap composition 串行回归通过。本切片没有新增或执行 migration。

本切片继续补齐 lease-safety 的 application 写入边界：新增按 `attemptId + epoch` 隔离的 `AttemptWriteGate`，租约安全中断通过 `disableAndDrain` 关闭当前 attempt 的本地写入窗口并等待在途 permit 退出；replacement epoch 仍可独立进入。Plain strong commit 与 terminal-only fallback 在调用 commit port 前都必须持有该 permit，闸门关闭时返回 typed `TURN_WRITE_GATE_DISABLED`，不会生成业务终态或 `plain_committed` 事件。新增 drain、epoch 隔离、Plain 拒写和 terminal fallback 拒写 contract tests；application 全量、app composition 与 infrastructure lifecycle/commit/lock/migration-control 定向回归通过。本切片没有新增或执行 migration。

本切片继续收口 attempt-level completion：`TurnV2TurnExecutor` 现在只向 delivery 暴露 `TurnAttemptCompletion`，仅 `PersistedTerminal` 允许代表产品终态；fence lost 映射为 `AttemptOwnershipLost`，write gate/未实现路由/运行时异常映射为 `AttemptSelfAborted`，terminal decoder 或 preparation unavailable 映射为 `StatusOnly`。terminal commit port 在这些非终态路径上不会被调用，generic execution error 也不会补写业务终态。新增 execution contract tests 覆盖 accepted attempt 传递、terminal replay、fence loss、write-gate 拒绝、runtime failure 与 unavailable；application、app composition 与 infrastructure lifecycle/commit/lock/migration-control 定向回归通过。本切片没有新增或执行 migration。

本切片把 heartbeat 接到 lease-safety application seam：新增 `TurnAttemptLeaseSupervisor`，按 accepted `LeaseTimingAnchor` 的 monotonic `renewWithin` 判断 due；renewal 重建同一 attempt 的新 lease/anchor，fence loss、已有 terminal 与 terminal schema unavailable 先调用 executor 的 `disableWritesAndDrain`，transient failure 只返回 retry-after。新增 renewal、monotonic due、fence-loss drain、terminal/unavailable drain 与 transient retry contract tests，并在 isolated V2 composition 条件注册 supervisor；application、app composition 与 infrastructure lifecycle/commit/lock/migration-control 定向回归通过。本切片没有新增或执行 migration。

本切片补齐 explicit cancel 与 V2 preparation 的 durable state fence：新增 `TurnAttemptExecutionStatePort`，在 Context 前、Context ready 后和 Decision 完成后以当前 attempt/epoch 重新读取状态；MySQL adapter 使用 `SELECT ... FOR UPDATE`，同时校验 terminal payload、attempt fence 与 lease 是否仍有效。取消或其他并发 terminal 已先落库时返回 `AlreadyTerminal`，executor 直接 replay 已持久化 outcome，不调用 terminal commit adapter；旧 attempt 与过期 lease 返回 ownership lost。新增 application、infrastructure 与 app composition contract tests；现有 `turn_execution` 字段足够承载该检查，本切片没有新增或执行 migration。

本切片继续修复 checkpoint CAS loser 的 current-read 语义：`MySqlTurnDecisionCheckpointAdapter` 的 `pinFirst` 在写入失败后改用 `SELECT ... FOR UPDATE` reload，避免取消/终态已赢得同一 `turn_execution` 行时，在事务快照中误读旧的 `RUNNING/MISSING`。新增 terminal-winner checkpoint adapter contract test，并回归 application、app composition 与 infrastructure M1 lifecycle/commit/lock/migration-control；现有表字段已足够，本切片没有新增或执行 migration。

本切片补齐 Context 非 Ready 分支的 cancellation precedence：`DefaultTurnV2PreHandlerCoordinator` 在 Context preparation 返回 `Ready`、`Terminal`、`FenceLost` 或 `Unavailable` 前统一重新读取 fenced execution state；若 explicit cancel/deadline 已先持久化 terminal，统一返回 `AlreadyTerminal`，不会把 Context terminal 再交给 terminal-only commit。新增 Context terminal 与 durable cancellation race contract test；application 全量回归通过，本切片没有新增或执行 migration。

本切片把 durable state fence 前移到 Plain dispatch 边界：`DefaultTurnV2ExecutionCoordinator` 在 pre-handler 返回 `Ready` 后、调用 Plain handler 前再次校验当前 attempt、epoch 与 lease；cancel/terminal/fence loss 会转为 preparation-blocked outcome，模型 generation 与 strong commit 均不会启动。isolated V2 composition 将同一 `TurnAttemptExecutionStatePort` 注入该边界，并新增 dispatch-race contract test；application 与 app composition 定向回归通过，本切片没有新增或执行 migration。

本切片补齐 accepted attempt 的 application lifecycle runner：新增进程内 `TurnHandle` 与 `TurnAttemptExecutionRunner`，由调用方注入 execution executor/scheduler；runner 从 accepted lease anchor 安排 heartbeat，续租后更新同一 attempt 的当前 lease，ownership loss/terminal/unavailable 先复用 supervisor 的 write-gate drain，再完成 typed attempt completion。detach 或 writer/serialization failure 只把 subscriber 替换为 no-op，不调用 cancellation port；scheduler/执行线程故障只产生 self-abort，不伪造 product terminal。新增正常 completion、detach、writer failure 与 heartbeat ownership-loss contract tests；application 全量与 app composition 定向回归通过，本切片没有新增或执行 migration，production HTTP 仍未接线。

本切片补齐 attempt-scoped deadline 的本地安全边界：新增 `TurnAttemptDeadlineSupervisor`，deadline callback 必须先取得同一 `TurnWriteGate` permit，并持有到 `AttemptDeadlineCancellationPort` 返回；gate 已因 lease-safety drain 关闭时返回 `WriteGateDisabled` 且 zero port call，stale epoch 仍由 durable CAS 返回 `FenceLost`。新增 gate-closed、permit 生命周期与 stale-epoch contract tests；application 全量回归通过，本切片没有新增或执行 migration。

本切片收口 deadline CAS 到 attempt completion 的 typed outcome：`DeadlineCancelOutcome.Cancelled` 现在携带 decoded `PersistedTurnOutcome`；MySQL deadline CAS 成功后使用 `SELECT ... FOR UPDATE` 解码刚写入的 terminal envelope，未知 schema 返回 typed unavailable。`TurnAttemptExecutionRunner` 的 optional execution deadline 将 `Cancelled/AlreadyTerminal` 映射为 `PersistedTerminal`，fence loss、schema unavailable、gate disabled 与 transient failure 保持 non-terminal typed completion。新增 deadline winner runner 与 adapter tests；application/infrastructure M1 回归通过，本切片没有新增或执行 migration。

本切片对齐 explicit cancel 的 durable outcome：`CancelTurnOutcome.Cancelled` 现在携带 MySQL CAS 成功后解码出的 `PersistedTurnOutcome`；adapter 在成功更新后使用 `SELECT ... FOR UPDATE` current read，terminal envelope 缺失或 schema 未知时返回 typed `TerminalUnavailable`，并保留并发 terminal winner 的 replay。新增 explicit-cancel winner adapter contract test；application、infrastructure 与 bootstrap composition 回归通过，本切片没有新增或执行 migration。

本切片补齐 explicit cancel 到本地执行资源的 signal seam：`TurnAttemptCancellationRegistry` 按稳定 `TurnKey` 注册当前 runner，`DefaultTurnControlFacade` 仅在 durable cancel CAS winner 后发送已持久化 outcome；`TurnAttemptExecutionRunner` 用可中断 execution task 关闭当前执行并在完成时注销注册。signal 只负责本地资源停止，断流仍只 detach，数据库 terminal CAS 仍是唯一真相。新增 facade signal 与 runner interrupt contract tests，application 与 bootstrap composition 回归通过，本切片没有新增或执行 migration。

本切片补齐 restart/takeover 的 pinned input recovery：新增 `turn_input_binding_json` migration，首次 claim 持久化 clarification、legacy declaration、current attachment refs 与 deterministic memory declaration；`MySqlTurnAttemptInputRecoveryAdapter` 在当前 attempt/epoch 与 lease fence 下，从 durable user message、attachment binding 和声明快照重建 `UserTurnCommand`，并重新验证 input digest。`TurnAttemptRecoveryCoordinator` 只有在 durable takeover 成功且 recovery 完整时才启动 `TurnAttemptExecutionRunner`；payload、binding、digest 或 fence 不一致均 typed fail closed。迁移已在本地 MySQL 执行并重复运行验证幂等；新增 codec、adapter、coordinator 与 migration contract tests，现有 M1 schema 之外仅新增该 recovery payload 列。生产 HTTP/legacy assignment 仍未切入 V2。

本切片把 pinned-input recovery 接入条件化 bootstrap composition：只有完整的 isolated V2 executor graph 才创建专用 scheduler、attempt runner 与 `TurnAttemptRecoveryCoordinator`；runner 明确绑定共享 `threadPoolExecutor`，scheduler 由 Spring 生命周期关闭，并使用 delegated scheduled executor，避免 `ScheduledThreadPoolExecutor` 的类型继承干扰共享执行池的 missing-bean 条件。legacy HTTP、legacy assignment 与无 V2 graph 的启动路径不创建这些资源；bootstrap contract test 覆盖 runner/coordinator 出现条件与资源组合。本切片没有新增 migration。

本切片补齐 M1 生命周期 trace 边界：新增 application-owned `TurnLifecycleTracePort` 与严格脱敏的 event model，submission facade 记录 assignment/claim，control facade 记录 cancel、heartbeat、takeover，attempt runner 记录 start、lease renewal、detach 与 completion。MySQL adapter 与 `2026-07-28-create-turn-lifecycle-trace.sql` 将这些记录持久化到独立表；trace 只暴露 canonical `TurnKey`、attempt epoch、policy/input binding digest、safe code/status 与时间，不接收用户正文、source body 或 terminal payload。trace adapter 故障也不会改变 durable lifecycle 结果；无 adapter 的 isolated graph 使用 no-op sink。迁移已在本地 MySQL 执行并重复运行验证幂等。

本切片补齐 checkpoint decision evidence：`DefaultTurnDecisionCoordinator` 在 load-first 命中、first-writer pin 和 CAS retry 时记录同一 `TurnDecisionCheckpoint.digest`，并沿用 attempt epoch、policy hash 与 input binding digest；`TurnPlannerCompositionConfig` 通过 `ObjectProvider` 将生产 trace adapter 接入，旧/isolated graph 仍可使用 no-op。新增 checkpoint contract test 验证 PINNED trace 的 decision digest 等于 durable winner；现有 `decision_digest` 列已足够承载，本切片没有新增或执行 migration。

本切片补齐 checkpoint 并发证据：两个 coordinator 在同一 fenced attempt 上同时经历 `Missing → compute → pin`，只有一个 first-writer winner，另一个 CAS loser 必须 reload winner，最终两个调用返回完全相同的 checkpoint/digest；trace 同时出现 `PINNED` 与 `CAS_RETRY`。application contract test 已通过；另以本地 MySQL 完全回滚的真实 fixture 验证 checkpoint JSON 写入与 `SELECT ... FOR UPDATE` current read，rollback 后 fixture 行数为 0。本切片没有新增 migration。

本切片补齐 takeover 后 recovery failure 的 lifecycle evidence：`TurnAttemptRecoveryCoordinator` 在 durable takeover 成功但 pinned input recovery 返回 `Unavailable` 或 `FenceLost` 时，记录同一 attempt/epoch、policy/input digest 与 safe code 的 `TAKEOVER` trace，并在启动 runner 前 fail closed；composition 通过 `ObjectProvider` 接入生产 trace adapter。新增 contract test 验证 recovery failure 不会启动 execution 且 trace 不丢失；本切片没有新增 migration。

本切片补齐 takeover 成功路径的端到端 lifecycle evidence：真实 `DefaultTurnControlFacade` 产出 `CLAIMED` `TAKEOVER` trace，`TurnAttemptRecoveryCoordinator` 从 pinned input 恢复 command，`TurnAttemptExecutionRunner` 随后产出 `ATTEMPT_STARTED` 与 `ATTEMPT_COMPLETED`；contract test 验证三类事件按顺序共享同一 attempt/epoch、policy hash 与 input binding digest，并得到 `PersistedTerminal(COMPLETED)`。本切片没有新增或执行 migration。

本切片收口 sticky assignment 的 diagram binding 与 live-flag retry 语义：`MySqlTurnEngineAssignmentAdapter` 复用已有 assignment 前再次比较持久化 `diagram_id`，不一致时返回 `DIAGRAM_BINDING_MISMATCH`；同一 TurnKey 的重试即使传入新的 policy snapshot/hash，也必须返回首次持久化的 policy。新增 adapter contract tests；现有 assignment 表已包含 `diagram_id` 与 policy 字段，本切片没有新增或执行 migration。

本补充把同一 binding invariant 前移到 application command：`TurnStartCommand` 现在拒绝与 pinned assignment 不同的 TurnKey 或 `diagramId`，避免错误 scope 在进入 atomic claim adapter 前继续传播；新增 immutable command contract test。本补充没有新增或执行 migration。

本切片补齐 Context 与 decision checkpoint 的 active-lease fencing：`MySqlTurnContextAdapter` 读取 execution 时同时比较 `lease_expires_at` 与同一查询得到的 `CURRENT_TIMESTAMP(3)`，过期 attempt 在 candidate/materialization 前返回 fence lost；`MySqlTurnDecisionCheckpointAdapter` 的 current-row 与首次 pin CAS 也要求 lease 仍有效，过期 attempt 不得继续写入 plan checkpoint。新增 adapter contract tests；现有 `turn_execution.lease_expires_at` 已足够，本切片没有新增或执行 migration。

本切片将 active-lease fence 收口到其余 M1/M2 durable write seams：Context read-set pin、attempt deadline CAS、terminal-only commit 与 Plain strong commit 都要求数据库时钟下 lease 仍有效；Plain commit 在锁定 execution 时先拒绝过期 attempt，避免触碰 Canvas/assistant message，Context read-set CAS loser 同时改用 `SELECT ... FOR UPDATE` current read。新增过期 lease 与 current-read contract tests；现有 `turn_execution.lease_expires_at` 已足够，本切片没有新增或执行 migration。

本切片补齐初次 claim 的 application handoff：`DefaultTurnDeliveryExecutor` 在存在 isolated `TurnAttemptExecutionRunner` 且 `TurnSubmission` 为 `ExecutionAccepted` 时启动同一 accepted attempt；没有 runner 的 legacy graph 保持 submit-only，status/terminal 仍由 durable lifecycle authority 提供。bootstrap composition 使用单一可选 runner 注入，并新增 application/composition contract tests；production assignment 仍未切入 V2，本切片没有新增或执行 migration。

本切片补齐 M1 control transport boundary：新增 `TurnHttpControlAdapter` 与 typed control request，status/cancel 在进入 `TurnControlFacade` 前统一经 `ConversationReferenceResolver` 解析 `default`、canonical 与 legacy alias，生成 owner-fenced canonical `TurnKey`；adapter 原样返回 sealed application outcomes，不自行决定 HTTP 终态或把 disconnect 转成 cancel。新增 canonical/legacy status 与 cancel contract tests；legacy controller、production assignment 与 V2 cohort 均未改变，本切片没有新增或执行 migration。

本切片补齐 compatibility transport 的 bootstrap composition：`TurnHttpRequestTranslator`、`TurnHttpDeliveryAdapter` 与 `TurnHttpControlAdapter` 现在共享同一个 application `TurnDeliveryExecutor`/`TurnControlFacade` graph；composition contract 验证 adapters 可用，但没有挂接 legacy `AgentServiceController`、新增 serving route 或改变 production assignment。app 全量仍只保留既有 `DefaultIntentRoutingServiceTest` 的 unrelated failure（`NONE`/`DIRECT`），本切片没有新增或执行 migration。

本切片固定 submission 到 HTTP disposition 的 transport contract：`ExecutionAccepted` 与 `AlreadyRunning` 映射 `202 Accepted + status endpoint required`，`TerminalReplay` 映射 `200 OK`，legacy retry expiry 为 `410 Gone`，fingerprint/admission conflict 为 `409 Conflict`，terminal schema unavailable/not-ready 为 `503 Service Unavailable`；`TurnHttpDeliveryResult` 暴露该映射但保留原始 sealed outcome 与 detach 标记。新增 mapper matrix tests，未改变 legacy controller、production assignment 或 V2 cohort，本切片没有新增或执行 migration。

本切片补齐 control transport 的 HTTP disposition contract：status 的 available outcome 为 `200 OK`，terminal decoder unavailable 为 `503 Service Unavailable`；explicit cancel 的 persisted winner 与 already-terminal replay 为 `200 OK`，terminal unavailable/readiness rejection 为 `503`，fence/race rejection 为 `409`，owner/not-found rejection 统一为 `404` 以避免泄露 durable turn。`TurnHttpControlAdapter` 保留原始 sealed outcome，并额外提供 `statusResponse`/`cancelResponse`；新增 control mapper matrix 与 canonical adapter tests，未接入 legacy controller、serving route 或 production assignment，本切片没有新增或执行 migration。

本切片接入 feature-gated V2 HTTP boundary：`TurnV2HttpController` 提供 `POST /api/v2/turns`、`GET /api/v2/turns/{turnId}/status` 与 `POST /api/v2/turns/{turnId}/cancel`，通过 `CurrentOwnerHttpResolver` 构造 owner-fenced `AuthenticatedActor`，并复用 delivery/control adapters 的既有 disposition。`turn-engine.http.v2.enabled` 缺省关闭，未开启时 controller 不注册；没有修改 `/api/v1` legacy controller、assignment selection 或数据库 schema。测试覆盖 owner 不来自 request body、status/cancel HTTP code 以及 feature flag annotation contract。

本切片补齐 submission-only NDJSON delivery boundary：`POST /api/v2/turns/stream` 与 sync submit 共享同一 `TurnHttpDeliveryAdapter`/`TurnDeliveryExecutor`，只发送安全的 `turn_submission` envelope 后结束连接；RUNNING attempt 继续由服务端执行并通过 status 查询，不承诺可重放进度事件。`NdjsonTurnEventSink` writer failure 仍只 detach subscriber，不转成业务取消；本切片未改变 legacy route、production assignment 或数据库 schema，也没有新增 migration。

本补充修正 submission-only stream 的真实实现边界：`TurnV2HttpController` 不再把执行进度事件接到已承诺只发送 submission 的 `ResponseBodyEmitter`，而是通过同一 `TurnDeliveryExecutor` 使用丢弃 progress 的 submission-only sink；因此快速执行不会把 progress 行混入 submission envelope，慢执行也不会在 emitter 完成后继续写入。执行、durable status/cancel 与断流不取消语义保持不变；`NdjsonTurnEventSink` 仍保留给需要真实 NDJSON 事件订阅的 adapter contract。本补充没有 schema 变化，也没有执行 migration。

本补充收紧 V2 serving 的 bootstrap 前提：`TurnV2HttpController` 除显式 `turn-engine.http.v2.enabled=true` 外，还要求 isolated `TurnAttemptExecutionRunner` 已由完整 V2 execution graph 组合；配置误开但 runner 缺失时不注册 route，避免 durable claim 后留下无人执行的 `RUNNING` attempt。新增 annotation contract test；legacy controller、assignment selection、production cohort 与数据库 schema 均未改变，本补充没有执行 migration。

本切片收口 status 的 not-found 语义：跨 owner 或不存在的 `turn_execution` 不再通过异常传播，而是返回 typed `TurnStatusQueryOutcome.NotFound`，HTTP control mapper 统一映射为 `404`，避免把资源存在性泄露为 `500`；新增 application、MySQL lifecycle adapter 与 HTTP mapper contract tests。本切片没有新增或执行 migration。

本补充处理 M1 review 的 release gates：旧 Router 单测恢复 `DIRECT` intent hint，`TaskSourcePlanner` 在没有可用 candidate 时明确 fail-closed 为 `DIRECT_SOURCE_MISSING`；application boundary test 改为读取实际 reactor POM，校验允许的反向依赖集合与 DAG 无环；`TurnApplicationCompositionConfig` 由 `turn-engine.lifecycle.enabled` 控制且默认关闭，避免 migration release 未完成时启动 durable claim/lifecycle graph。新增 `release-20260729.manifest` 与对应 migration image，按依赖顺序打包 2026-07-26 至 2026-07-28 的三份既有 M1 SQL，并在部署文档中要求 migration 成功后才开启 lifecycle；本补充没有新增 schema DDL，也没有执行生产 migration。

本补充修复 2026-07-26 review 的三个 P0 与四个 P1：Direct/Direct+Retrieval 在缺少可用 candidate 或 source resolution 失败时保留 required 语义并返回 typed `DIRECT_SOURCE_MISSING`，不再降级为 Plain/Retrieval；`AttemptLease` 将 heartbeat budget 固定为相对 expiry 的一半并测试严格早于 expiry；takeover recovery SQL 使用真实 schema 的 `diagram_conversation_message.user_id`，并新增可选的真实 MySQL schema-backed recovery test。重复 current-turn attachment 在 `TurnDeclarations` admission 前校验；M1 暂不接受 `V2_CANARY`，避免 canary 标记实际执行 Legacy；clarification 在 durable authority 尚未实现前以 `CLARIFICATION_DEFERRED` rejected terminal 明确收口；CI 的 PR build 与 main publish 都显式构建 `Dockerfile.20260729`，以 commit SHA tag 推送 migration image 并发布 digest。以上没有新增 schema DDL；20260729 migration 已在本地 MySQL 执行并重复运行验证幂等，未执行生产 migration。

本轮完成控制面的最终验收并关闭本票据：application M1 定向回归 114/114、trigger transport/control 23/23、domain source-planning 及边界 300/300、infrastructure M1 定向回归 56/56 全部通过；其中 `MySqlTurnAttemptInputRecoverySchemaTest` 使用本地 Docker MySQL 的真实 schema 与密码执行通过，确认 recovery 查询使用生产列名 `diagram_conversation_message.user_id`。全 reactor 曾受本机 JDK 23 Mockito attach、旧 `ApiTest` 空测试，以及受网络隔离影响的 Pinecone live evaluation 阻断；这些不属于本票据的生命周期控制失败。`turn-engine.lifecycle.enabled` 仍保持默认关闭，必须在生产按 `release-20260729.manifest` 完成 migration task 后再开启；生产 HTTP/legacy assignment 切入与 V2 cohort 仍不在 M1 本票据范围内。

## single-instance-migration-control: Build The Single-Instance Migration Boundary

Blocked by: turn-execution-control
Status: resolved
Type: Task

### Question

单实例部署如何保留 sticky assignment 和安全切流，同时删除分布式迁移控制？

### Answer

DB singleton lock、启动 orphan reconciler、application admission gate 以及 M1 bootstrap composition 已落地；迁移 pause/drain、backfill 与 mode switch 的生产 composition 仍待接入。前提是部署或 DB singleton lock 保证任意时刻只有一个 serving instance：

- 建立 `turn_engine_migration_state(mode,generation)` singleton row；
- 启动先取得 `SingleActiveInstanceLock`，失败则不开放 HTTP admission；
- `TurnApplicationCompositionConfig` 已把 conversation resolver、deterministic admission profile、assignment service、turn facade 与 gate 组成同一 application graph；`ApplicationRunner` 按 lock → orphan reconcile → open admission 顺序启动；
- `AdmissionBarrier.pauseAndDrain()` 通过 Facade 的 `tryEnter/leave` 登记本地 in-flight admission，停止新 turn 并等待已进入调用归零；
- `TurnEngineMigrationCoordinator` 已把 pause/drain、migration singleton row 的 compare-and-switch 与 resume 串成一个 seam；`MySqlTurnEngineMigrationControlAdapter` 在同一事务锁 row、递增 generation 并以 DB clock 写 `switched_at`，CAS 失败保持旧 mode；
- 同一 coordinator 已提供 DB-clock expiry scanner seam：暂停本地 admission 后批量把到期 `LEGACY/EXECUTABLE` assignment 写入 `legacy_turn_tombstone` 并标记 `EXPIRED_GONE`；scanner 异常通过 `finally` 恢复 admission。
- `TurnControlFacade` 已统一 application 内的 status、explicit cancel、heartbeat、attempt-deadline cancel 与 takeover；takeover 在调用 durable port 前验证 authenticated owner，旧 HTTP 仍未切入该 facade。
- pause window 内 backfill retryable legacy assignment、写 Gone tombstone并运行 expiry scanner；
- mode switch 在 migration row transaction 中递增 generation；existing assignment 继续 sticky；
- restart 先 reconcile 前一 boot 的 orphaned executions，再开放 admission；
- 保留 typed `LegacyRetryExpiredAdmission → HTTP 410`；
- 不实现 durable dispatch permit、dispatch heartbeat、write fence 或 cross-instance coverage barrier。

这里只删除 migration 专用的 legacy dispatch lease；V2 turn 的 attempt lease、epoch fencing、原子 claim 与 terminal CAS 继续保留。

drain/backfill 失败时保持旧 mode 并恢复 admission；singleton lock 丢失时关闭 admission、标记 not-ready 并终止进程。

若未来允许实例重叠或水平扩容，本票的简化 profile 失效，必须恢复 distributed migration protocol。

本轮收口生产组合：`TurnApplicationCompositionConfig` 的 startup runner 仍默认只执行 singleton lock → orphan repair → open admission；只有显式设置 `TURN_ENGINE_MIGRATION_STARTUP_TARGET_MODE` 时，才在同一 runner 中调用 `TurnEngineMigrationCoordinator`，由 admission drain 包住 retry backfill、expiry tombstone scanner 和 durable generation/mode CAS。目标 mode 非法或 durable transition rejected 会抛出启动失败，避免半迁移实例继续 serving；`V2_CANARY` 仍由 M1 明确拒绝，不绕过 stable cohort。数据库 migration 没有新增；部署手册已记录该 one-shot hook。composition 5/5、application migration/admission 13/13、真实 MySQL infrastructure 56/56 通过。

## all-path-v2-canary: Run A Sticky All-Path Canary

Blocked by: single-instance-migration-control, unified-turn-delivery, plain-boundary-evals, source-aware-boundary-evals
Status: resolved
Type: Task

### Question

如何在 all-V2 mode switch 前验证真实 production auth、commit 与 delivery wiring？

### Answer

已完成 stable all-path canary wiring，但 production 默认仍保持关闭：只有通过现有 singleton migration hook 显式切换到 `V2_CANARY`，并配置 allowlist、rollout percent 或 versioned salt 后才会产生 V2 assignment。

`StableTurnEngineCohortSelector` 只使用 authenticated actor 的 stable cohort key、allowlist、百分比和 SHA-256 rollout salt；prompt、locale、附件、source availability、Profile/Memory、模型输出和 delivery channel 都不进入选择输入。同一 cohort 在同一 salt 下稳定落入 V2 或 legacy。

`TurnEngineAdmissionService` 在 assignment 前完成选择，并把 `SelectedTurnEngine` 写入 durable assignment command；`MySqlTurnEngineAssignmentAdapter` 在 migration row 锁内校验 mode/selection 一致性。`assignOrReuse` 仍先解析 existing/tombstone，真正 unseen key 才按 stable cohort 得到整轮 V2 或整轮 legacy assignment；retry/restart 不重新抽 cohort。

canary 不区分 Plain 与 source-aware，也不读取 prompt、locale、附件或模型输出。sticky assignment 已保证同一 TurnKey 的重试不改道，因此不需要 admission grant reservation。生产 assignment 仍受 `turn-engine.lifecycle.enabled` 保护，不因本票自动打开 serving。

`LEGACY → V2_CANARY` 只能经暂停 admission、drain、retry backfill/expiry tombstone 与 durable generation/mode CAS；`V2_CANARY → LEGACY` 使用同一 migration fence 作为显式 rollback，既有 V2 turn 继续由兼容 executor 完成。现阶段 rollback 是 operator/migration hook 驱动，自动 error-budget 监控不在本票内。

测试覆盖稳定 cohort/allowlist/边界、assignment selected-engine 持久化、mode generation race、startup orphan reconciliation、canary migration 和同窗 rollback；application 194/194、domain 302/302、trigger 40/40，相关 infrastructure/app 定向测试 14/14 与 5/5 通过，完整相关 Maven reactor `BUILD SUCCESS`。本票没有新增或执行数据库 migration。

## all-v2-cutover: Activate The M6 Boundary

Blocked by: all-path-v2-canary, session-continuity, conversation-scope-migration
Status: resolved
Type: Task

### Question

何时允许把所有真正 unseen turn 切到 V2？

### Answer

已完成 all-V2 cutover 门禁。只有先处于已验证的 `V2_CANARY`，才允许 pause admission、drain 本地 legacy in-flight，并在同一 migration row transaction 中切到 `ALL_V2`；application 层直接拒绝 `LEGACY → ALL_V2`，不触发 pause、backfill、expiry 或数据库写入。

mode switch 与 `assignOrReuse` 锁同一 migration row。generation changed 由 durable adapter 返回 typed rejection，调用方必须基于最新 snapshot 重建 command；恢复 admission 后 unseen routing 固定 `ALL_V2`，并由 assignment adapter 校验新选择只能是 `V2`。

transaction 固定 legacy retry horizon，并先完成 backfill 与 expiry tombstone。旧 key/tombstone 继续返回 `410 LEGACY_RETRY_EXPIRED`；existing executable legacy/canary assignment 按保存 engine 与 policy 继续服务到 expiry，不能因当前 mode 改写 assignment。

`ALL_V2` 后，当前 legacy DTO compatibility adapter 不接入 V2 composition，因此本票不会为 legacy-only shape 新建 assignment；未来若接入不支持的 legacy shape，必须在 transport boundary 返回 typed `UPGRADE_REQUIRED`。switch 后有 expired assignment 的旧请求返回 410；只有无旧证据的 key 才能作为 unseen V2。

测试覆盖 guarded pause/drain、mode generation race、startup/restart reconciliation、旧 key 410、unseen V2、existing legacy/canary sticky assignment、backfill/expiry batching 与 rollback。application `195/195`、domain `302/302`、trigger `40/40`、infrastructure `274`（5 个外部服务测试跳过）、app `900/900` 全部通过；本票没有新增或执行数据库 migration。

## unified-turn-delivery: Unify Sync And Stream Attempt Results

Blocked by: turn-lifecycle-evals, source-aware-commit-seams, source-aware-boundary-evals
Status: resolved
Type: Prototype

### Question

M6 全量 V2 前，如何证明 sync/stream 不再是两套业务执行？

### Answer

统一 delivery boundary 已落地并通过 M6 gate：

- `TurnHttpDeliveryAdapter` 的 canonical sync、NDJSON 与 submission-only stream 入口现在都经过同一个 `executeCanonical`，只替换 `TurnEventSink`；legacy DTO 仍保留独立 compatibility translator。
- V2 stream 保持 v1 的 submission-only 合同：只返回 `turn_submission`，RUNNING 继续由 server-owned runner 执行并通过 status 查询，避免 emitter 结束后再写进度。
- 新增 `UnifiedTurnDeliveryMatrixTest`，覆盖 Plain、Direct、Grounded、Evidence Answer × `PersistedTerminal`、`AttemptOwnershipLost`、`AttemptSelfAborted`、`StatusOnly(TERMINAL_SCHEMA_UNAVAILABLE)` 共 16 个组合。两条 transport 都得到同一 `TurnSubmission` 与相同 attempt completion；只有 `PersistedTerminal` 保持产品终态，其余均保持 status-only 语义。
- NDJSON writer 主动失败时只触发 sink detach；同一 execution completion 仍返回，未生成取消、第二份 outcome 或伪造 terminal。既有 runner 测试继续覆盖 explicit cancel、deadline CAS、lease ownership loss 与 commit race；terminal decoder unavailable 继续走 typed status-only。

本票只修改 delivery adapter 与 transport gate tests，没有新增或执行数据库 migration；production assignment 仍未切换，all-path canary 可以解除本票阻塞后继续等待其余 gates。

## legacy-retirement: Remove Legacy Execution Safely

Blocked by: all-v2-cutover
Status: resolved
Type: Task

### Question

何时可以在 M9 删除 legacy executor 与 migration ingress？

### Answer

已完成 legacy executor retirement entry gate。migration coordinator 在 admission pause/drain 后继续使用 DB time scanner：先批量 backfill legacy retry metadata，再把到期 executable assignment upsert 为 tombstone 并原子标记 `EXPIRED_GONE`；两个 scanner 都排空后才读取最终 readiness。

新增 `LegacyRetirementGatePort`，MySQL adapter 用一个 DB-time 查询同时核验四项条件：`EXECUTABLE` assignment 为零、retry horizon 未结束的 assignment 为零、`EXPIRED_GONE` assignment 不得缺 tombstone、tombstone retention 不得已过期。任一计数非零，或 readiness 不可用，均返回 typed `LEGACY_RETIREMENT_NOT_READY`，不写 migration mode，并在 finally 恢复 admission。

只有 `ALL_V2 → RETIRED` 且四项计数全为零时才执行 durable generation/mode CAS。`RETIRED` 下 admission 在 assignment 写入前返回 `TURN_ENGINE_RETIRED`；adapter 不允许从 `RETIRED` 回滚到旧 mode，因此旧 artifact 不能重新创建已无 executor 的 `LEGACY/V2_CANARY` assignment。restart 仍先执行 singleton lock 与 orphan reconciliation，显式 retirement target 随后才运行最终 scanner 和 readiness gate。

测试覆盖最终 scanner 后的 retirement success/rejection、四项 readiness SQL 计数、`RETIRED` admission fail-closed、restart composition、old-key `LEGACY_RETRY_EXPIRED` 与既有 migration CAS。application `198/198`、domain `302/302`、trigger `40/40`、infrastructure `275`（5 个外部服务测试跳过）、app `900/900` 全部通过；本票没有新增或执行数据库 migration。

## docs-reconciliation: Reconcile Superseded Source Semantics

Blocked by: formal-contract-adr
Status: resolved
Type: Task

### Question

哪些旧文档需要加 superseded 标记或改写，才能只保留一份来源链合同？

### Answer

已完成五份旧文档的规范收口：根目录 `CONTEXT.md`、2026-07-18 PRD、2026-07-19 Technical Design、2026-07-23 Completion Plan 和 2026-07-24 Source Chain Redesign 均增加 ADR 0013 的 superseded precedence 标记；冲突章节额外标为历史语义/历史顺序。

保留内容明确限定为 owner/scope、lifecycle/retention、citation persistence、ingestion、security 与 target resolution。已显式废止 Personal Library AUTO、per-message source selection/checkbox、旧 `SourceMode`、Router 前置 Source Probe/snapshot、断流取消产品 turn 和无 atomic claim 的 turn sequencing；新实现统一遵循 assignment → claim/binding → Base Context → typed demand/plan → 条件 source I/O，disconnect 只 detach。

本票只修改文档与本地图状态，没有数据库 schema 变化，也没有执行 migration；`git diff --check` 与文档标记/链接存在性检查通过。

## source-free-v2-path: Isolate The M2 Source-Free Path

Blocked by: formal-contract-adr, turn-execution-control
Status: resolved
Type: Prototype

### Question

如何在不按 prompt 拆分 production engine 的前提下，先完成普通画图的物理隔离？

### Answer

M2 已开始，先交付不改变 production assignment 的 application/transport 切片：

- 新增固定 source-free `PlainRuntimeRegistry`、`PlainExecutionProfile`、`PlainGenerationPort` 与 `PlainDrawingHandler`；Plain CREATE/EDIT/LAYOUT 只能通过 tool-free generation port，并以既有 `PlainTurnCommitPort` 做一次 fenced strong commit。
- 新增 `TurnDeliveryExecutor`，同步与 NDJSON 只替换 `TurnEventSink`，共享同一个 `DiagramTurnFacade`；writer failure 只 detach，不构造取消或产品终态。
- 新增 `trigger.http.turn` compatibility translator。旧 `ChatRequestDTO` 的 owner、canvas、history 不进入 canonical command；`selectedLibraryVersionIds` 只映射为 untrusted legacy declaration，opaque current-message attachment 仍由 canonical DTO 显式携带。
- 新增 restricted-input demand contract 与无 I/O `SourceDemandResolver`：只验证 current-instruction evidence、message attachment binding、固定 confidence policy、Chartbook membership 与 signed Plain fallback；不读取 source availability、body、retrieval 或 citation。
- 新增 buffering/NDJSON sink 与 detach 测试，覆盖 Plain generation failure 不得到达 commit，以及 writer failure 不得回写业务终态。
- 新增 application-owned `SemanticIntentRouterPort`、`TurnClassificationService` 与 `PlainDrawPlanFactory`：router 与 demand interpreter 必须共享 current-instruction digest；任一 port unavailable 都 typed fail；只有 resolver 确认的 `NoSourceDemand` 才能进入 Plain plan，accepted source demand 与 unsupported action fail closed。
- 新增 server-owned `BaseTurnContext` 与 closed `ContextRead` states；`DefaultSemanticRouterContextProjector` 只投影 Canvas/Conversation/Chartbook Profile/confirmed Memory，`DefaultRestrictedSourceDemandInputFactory` 只投影 current instruction、opaque current-message attachment refs、membership identity 与 safe clarification labels。
- 新增独立 tool-free V2 Semantic Router / Source Demand agent 配置与 infrastructure adapters：fresh session、empty tool allowlist、严格 JSON fields 和 typed unavailable；V2 composition 通过 conditional graph 注册，但不改变 legacy production assignment。

具体 domain-version adapters/materialization backend 与 production V2 assignment 仍待后续 M2 切片；production assignment 继续全部 legacy。

本切片已补齐 durable seam：`ContextReadSet` 固定 conversation high-water 与 summary/membership/Profile/Memory exact pin，`ContextReadSetQueryPort`/`ContextReadSetCommitPort` 采用 load-first + fenced first-writer CAS；`TurnDecisionCheckpoint` 固定 read-set/input-binding digest 与 bounded canonical decision payload，并由独立 query/commit port 以同样规则持久化。MySQL adapter 使用既有 `turn_execution.context_read_set_*` 与 `plan_payload_*` 字段，CAS loser 只能 reload winner；缺失、损坏、stale、revoked 都返回 typed outcome。新增 migration contract test，但没有新增 migration。真实 domain-version materialization backend 与 production V2 assignment 仍待后续切片。

本切片继续补齐 application seam：`ContextAssemblyCoordinator` 在 Router 前执行 load-first；只有 Missing 才调用 `ContextCandidateQueryPort`，pin 后始终通过 `ContextReadSetMaterializerPort` 按最终 winner 的 exact read-set 重建 `BaseTurnContext`，CAS loser 与 bounded retry 不会沿用 live candidate。`DefaultPrePlanner` 只输出闭合的 `SourceFreeReady`、带 lineage/input/read-set digest 的 `SourcePlanningRequired` 或 typed clarification/unsupported/unavailable；`DefaultTurnRouteDispatcher` 只做纯路由转换，不访问 Probe、Snapshot、Evidence 或 source body。

本切片新增 `DefaultTurnRouteComputer`，将 Context projection、双模型 classification、Resolver、Pre-Planner 与纯 route dispatcher 收口为无 source I/O 的 pre-probe 计算边界；新增 `TurnDecisionCoordinator` 执行 load-first、Missing 才计算、fenced first-writer pin、CAS loser reload winner，并在 context/input digest 不匹配或 decoder 失败时 fail closed。`FastjsonTurnRouteDecisionCodec` 固定当前 plain/source-planning/clarification/unsupported/unavailable route 的 bounded versioned payload；新增 application、infrastructure 与 app contract tests。具体 domain-version adapters、真实 context query/materializer backend、Source Probe/source-aware Planner wiring 与 production V2 assignment 仍待后续切片。

本切片进一步交付 `MySqlTurnContextAdapter`，同时实现 candidate query 与 exact materializer：candidate 复用现有 `diagram`/`diagram_canvas_state`/`chartbook` latest row，只 pin canvas version、projection digest 与 chartbook membership revision；materializer 再次校验同一 version/digest，发生并发修改时返回 Retry，绝不以新 row 冒充历史版本。它按 canonical conversation high-water 读取已提交消息，并从 durable attachment binding 恢复 opaque ref 的 display name/declared MIME；Profile、Memory、Clarification、Selection 仍返回显式 Absent/diagnostic，不从旧 source 配置或 `preferences_json` 推断。当前 schema 已足够承载这一 transitional backend，因此本切片没有新增 migration；真正 immutable domain-version stores、Profile/Memory backend、Source Probe/source-aware Planner wiring 与 production V2 assignment 仍待后续切片。

本切片修复 application seam：`ContextPreparationOutcome.Ready` 现在同时携带 `BaseTurnContext` 与 assembly 阶段最终 winner 的 `ContextReadSet`，因此后续 Router/Decision Coordinator 可以复用同一 read-set digest 与 message high-water，而不会重新读取或丢失 pin。新增 contract test 覆盖该 continuity；本切片仍不需要数据库 migration。

本切片进一步接上隔离的 `TurnV2PreHandlerCoordinator`：已 claim 的 `FencedAttempt` 先经过 `ContextAssemblyCoordinator`，再把同一个 context/read-set 传给 `TurnDecisionCoordinator`，并将 ready、terminal、fence-lost、unavailable 统一为 typed outcome。`Ready` 额外校验 attempt high-water、checkpoint digest 与 route decision 自身的 context/input digest；app composition 仅在两个 preparation seam 都存在时注册，未改变 production assignment 或 HTTP legacy route。Plain/source-aware handler dispatch 与 strong commit 仍待后续切片，本切片没有新增 migration。

本切片把 Plain 路径推进到可验证的 handler/commit boundary：`PlainGenerationRequest` 现在携带已 pin 的 `BaseTurnContext` 与 `ContextReadSet`，生成结果必须同时提供 canvas XML、assistant message 与 payload ref；`PlainDrawingHandler` 从 Plain route 读取 canvas pin 并创建带 expected version/context digest 的 `PlainTurnCommit`。新增 `MySqlPlainTurnCommitAdapter`，在一个事务内按 attempt fence 校验，执行 Canvas CAS、assistant message 写入和 `turn_execution` terminal update；terminal replay、stale fence、canvas version/digest conflict 都在写入前退出。现有 M1 schema 已包含所需字段，本切片未新增或执行 migration。Source-aware ports、真实 Plain model adapter、production V2 assignment 与全 transport executor 仍待后续切片。

本切片进一步交付 isolated `TurnV2ExecutionCoordinator`：它只在 `TurnV2PreHandlerCoordinator` 返回 `Ready + Plain` 时调用 `PlainDrawingHandler`；SourcePlanning、Clarification、Unsupported 与 Unavailable route 返回 `NotDispatched`，Context terminal/fence-lost/unavailable 返回 `PreparationBlocked`，不会把未实现路径伪装成 product terminal。`TurnV2ExecutionCompositionConfig` 仅在 Plain generation/commit ports 同时存在时注册 Plain runtime、profile、handler 与 executor；无这些 ports 时仍只保留 preparation seam，production assignment 与 legacy transport 不变。本切片没有新增 migration。

本切片补上 claim handoff contract：`TurnV2TurnExecutor` 只接收 `TurnSubmission.ExecutionAccepted`，再把该 accepted attempt 交给 isolated route coordinator；`ExecutionAccepted` 同时校验 `TurnKey`、`FencedAttempt.key` 与 lease anchor 一致。app composition 只注册该 executor，不接入当前 Legacy HTTP/transport，因此没有扩大 production assignment。本切片没有新增 migration。

本切片进一步把明确 terminal 接入 handoff：pre-handler 的 `Terminal` 使用 accepted attempt 通过 `TerminalOnlyTurnCommitPort` fenced 写入 `FAILED`，deterministic `Unsupported` route 写入 `REJECTED`；fence-lost、unavailable、source-planning 与 clarification 仍返回 typed non-terminal outcome，不伪造产品终态。executor 只有在 terminal commit port 存在时才由 app composition 注册；Legacy HTTP/production assignment 仍不变。本切片没有新增 migration。

本切片补齐 M2 的 response/review strong commit seam：`ResponseTurnCommit` 固定 diagram scope、assistant message 与 payload ref；`MySqlResponseTurnCommitAdapter` 在同一事务内锁定 active attempt/conversation、推进 conversation high-water、写 assistant message 并以 lease/epoch CAS 完成 `turn_execution`。terminal replay、stale/expired attempt 与 missing conversation 在触碰业务写入前返回；terminal CAS 在初始检查后失去 fence 时抛出并依靠事务回滚 message/conversation 写入。新增 4 项 adapter contract tests 与 Plain adapter 回归共 9 项通过。本切片复用现有 M1 message/turn_execution 字段，没有新增或执行 migration；response handler、source-aware Planner 与 production V2 assignment 仍待后续切片。

本切片新增隔离的 `ChatPlainGenerationAdapter` 与 `PlainGenerationPromptRenderer`：只将 bounded Canvas summary、Conversation、Chartbook Profile、confirmed Memory 和 Plain plan 投影给 tool-free fresh session；current-message attachment 的 ref、MIME、display name 与任何 source body 均按 source-free contract omitted。适配器默认由 `zipp.turn.v2.plain-generation.enabled=true` 才注册，严格要求三字段 JSON、bounded assistant/payload、无 DOCTYPE/ENTITY 的 `mxGraphModel`，模型不可用或输出不合约时在 strong commit 前 fail closed。新增 3 项 adapter contract tests，完整 Maven reactor 通过；本切片没有新增或执行 migration，production V2 assignment 仍未改变。

本切片补齐 source-free response handler seam：新增 `PlainResponseKind`、`PlainResponsePlan`、bounded `PlainResponseGenerationPort`/request/result 与 `PlainResponseHandler`。ANSWER/REVIEW/DIRECT_REPLY 只生成 assistant message + payload ref，再调用已有 `ResponseTurnCommitPort`；没有 Canvas mutation，generation failure、context high-water mismatch 或 write-gate disabled 都不会写入 response/terminal。app composition 仅在 response generation 与 response commit 两个 port 同时存在时注册，默认不改变 production assignment。新增 3 项 handler contract tests 与 conditional wiring test；本切片没有新增或执行 migration。

本切片新增隔离的 `ChatPlainResponseAdapter`：使用 fresh tool-free session，将 bounded Canvas/Conversation/Profile/Memory 与 `PlainResponsePlan` 投影为 `PLAIN_SOURCE_FREE_RESPONSE_V1`，明确省略 current-message attachment metadata，严格只接受 `assistantMessage` 与 `payloadRef` 两个 JSON 字段，并将模型不可用/输出非法映射为 typed unavailable/invalid。adapter 默认由 `zipp.turn.v2.plain-response.enabled=true` 才注册；app composition 另外支持 response-only profile，避免响应路径依赖绘图端口。新增 3 项 adapter contract tests 与 response-only wiring regression，完整 Maven reactor 通过（1655 项，0 failures，9 skipped）；本切片没有新增或执行 migration，production V2 assignment 仍未改变。

本切片把 response/review seam 接入 isolated pre-plan/execution：`PlainResponsePlanFactory` 只在 `NoSourceDemand` 下接受 `ANSWER/TEXT`、`REVIEW/REVIEW` 与 `DIRECT_REPLY/TEXT`，生成带 lineage、context digest 和 input binding 的 `SourceFreeResponseReady`；`TurnRouteDecision`、versioned Fastjson checkpoint codec 与 `TurnV2PreHandlerOutcome` 均覆盖该新分支。`DefaultTurnV2ExecutionCoordinator` 在 handler 存在时调用 `PlainResponseHandler` 和 `ResponseTurnCommitPort`，否则 typed `PLAIN_RESPONSE_HANDLER_NOT_AVAILABLE`，不伪造终态。新增 planner/coordinator/codec contract tests，仍未改变 production assignment，也没有新增或执行 migration。

本票同时 owns DTO 的 `currentTurnAttachments`、hidden clarification id、opaque refs 与唯一 compatibility translator。

composer 上传成功只创建 Conversation File。发送消息时，`TurnStartCommitPort` 才把 opaque refs 与该条 user message 原子绑定。

Demand Interpreter 只读取 current instruction、附件 metadata/binding、Chartbook membership identity 和安全 clarification labels。

它不能读取 Conversation、Profile、Memory、source availability 或 body。proposal 必须给出 current-instruction evidence span。

Resolver 只验证 evidence digest、message attachment binding、typed referent 与 pinned model/policy threshold。它不做多语言关键词解析，也不执行 Source I/O。

M2 的 isolated executor 覆盖 source-free path。离线 comparison 只记录 proposal/decision，不能调用生产 Probe、执行 mutation 或影响 legacy 响应。

Plain handler 的构造与 Maven/ArchUnit 边界不得引用 Probe、Snapshot、Material、RAG、Evidence、citation 或 source-aware adapter。

Plain 只能注入 tool-free `PlainGenerationPort` 与固定 execution profile；runtime registry 必须拒绝 Source、Material、Retrieval、Memory-management 等工具能力。

source dependencies 全部故障时，isolated Plain CREATE/EDIT/LAYOUT 仍按各自 Canvas/Context requirement 完成。

测试证明 SourceFreeReady 零 source 调用、Conversation File 不等于 current-message attachment，以及附件绑定可在 restart/takeover 后重建。

M6 前不建立 Plain-only production cohort。这样 Router 的多语言误判不会决定 legacy/V2 engine，也不会造成两套路径语义漂移。

本票最后收口 response-only composition：`TurnV2ExecutionCoordinator` 不再强制依赖 `PlainDrawingHandler`，仅有 `PlainResponseHandler + ResponseTurnCommitPort + TerminalOnlyTurnCommitPort` 时也能创建 isolated V2 executor；绘图 handler 缺失时 Plain route 明确返回 `PLAIN_HANDLER_NOT_AVAILABLE`，不会误调用或伪造终态。新增 response-only coordinator 与 bootstrap composition contract tests，定向测试和完整 Maven reactor 均通过（1658 项，0 failures，9 skipped）。本票未改变 production assignment，也没有新增或执行 migration。

## source-execution-plan: Build The Deterministic Source State Machine

Blocked by: source-free-v2-path, context-envelope
Status: resolved
Type: Prototype

### Question

如何把当前 `AUTO snapshot + Router fields + service branches` 收敛为单一确定性执行计划？

### Answer

确定性 pre-probe 状态机已收口；以下设计约束已经落到 application/infrastructure contract，source I/O 仍由后续 source-aware tickets 接入：

- proposal 绑定完整 restricted-input digest、model version、policy version；Resolver 校验 current-instruction evidence、message attachment binding、referent/query 一致性和 pinned policy，stale/invalid 输入均 typed fail closed。
- DefaultPrePlanner 只产出 SourceFreeReady、SourceFreeResponseReady、SourcePlanningRequired、NeedsClarification、Unsupported 或 Unavailable；DefaultTurnRouteDispatcher 只做纯类型路由，不访问 Probe、Snapshot、Evidence 或 source body。
- PlanningLineageFingerprint 纳入 pinned Context/Input digest 及 demand provenance；TurnDecisionCheckpoint 继续负责 first-writer CAS、retry/restart/takeover 的唯一 decision authority。
- ChatSourceDemandInterpreterAdapter 使用 fresh tool-free session、严格 JSON fields，并在 adapter 与 Resolver 两层校验 proposal binding；模型输出 stale/unknown/invalid 时 typed unavailable。

本票验证的是 pre-probe 确定性边界，不声称已经实现 Probe、typed source-aware Planner、clarification durable authority、snapshot freeze 或 Direct/Composite strong commit；这些由 optional-enrichment、direct-composite-hardening 与 source-aware-commit-seams 继续完成。application source-demand/classification/planner 定向测试 15/15、infrastructure V2 model adapter 6/6 通过；没有新增或执行数据库 migration，production assignment 仍保持 legacy。

- Semantic Router 给出 action/target/follow-up；restricted-input Demand Interpreter 独立给出 `SourceDemandProposal`；
- 两个 port 在 Base Context pin 后可以并行执行，但使用不同 renderer；
- Semantic Router 可见正常 Base Context；Demand Interpreter 只见 current instruction、message attachment binding、membership identity 和安全 clarification labels；
- proposal 携带 current-instruction evidence span、confidence、reason 和可选 relevance query；
- 无 I/O 的 `SourceDemandResolver` 校验 evidence/input digest、message binding、typed referent 与 versioned policy；
- Required 只有在 approved model/policy、阈值和清晰 referent 同时满足时成立；弱指代进入 clarification；
- 任一模型 port decode/调用失败时 typed unavailable，零 source/业务 mutation；
- Resolver 产出 requiredness 与 typed current-attachment/exact/named/project referent；Pre-Planner 不得重解析 raw text；
- Profile、Memory、Conversation history 与 availability 不能替代 current-instruction evidence；
- `OptionalSourceDiscoveryDemand` 携带任务相关性 query；它不要求用户说出“资料”关键词；
- standalone Diagram 的 Optional Discovery 转 SourceFree；Chartbook turn 才允许 conditional relevance Probe；
- terminal-only commit 原子保存 stable clarification id、immutable option set/digest、opaque candidate ref、observation 与 expiresAt；
- 用户自然语言回复 clarification；hidden id 绑定行，Router 提议 option，`ClarificationReplyResolutionPort` 逐值校验 owner/set/option/candidate/expiry；
- 聚合结果由 `TurnDecisionCheckpoint` 固定；retry/restart/takeover 不重跑两个模型 port，因此 turn 级行为可复现；
- deterministic Pre-Planner 先产出 `SourceFreeReady` 或带 demand provenance 的 `SourcePlanningRequired`；
- `SourceFreeReady` 直接进入 Drawer，不 probe/resolve/freeze sources；
- Direct current-attachment 只解析 claim 保存的 message binding；历史 Conversation 图片不能伪装成本轮附件；
- “根据这个附件”可把同一 binding 投影为 exact Retrieval scope；artifact/media capability 仍在 Probe/freeze 时校验；
- selected source 使用 exact scope；PROJECT_AUTO 使用 Conversation、Diagram 与 Chartbook，永不访问 Personal Library；
- Optional Discovery 只有在 availability 非空时进入 Probe；no-match/unavailable 只能返回 signed `ProbeFallbackReady`；
- Probe 只返回 availability/relevance facts，不向两个模型 port 或 Drawer 注入 source body/chunks；
- Pre-Planner 在 Probe 前签发 planning lineage，Planner 生成的 bound plan identity 必须原样穿过 freeze/prepare/commit，handler 不得重算；
- Probe command 接收完整 `SourcePlanningRequired`；outcome 与每个 candidate fact 回显 turn/lineage/declaration digest binding；
- Direct candidate fact 是 origin-specific atomic tuple；Planner 只能映射成对应 sealed selector variant，不能组合非法 identity/proof；
- Composite availability 按 DIRECT/RETRIEVAL role 返回事实，typed Planner 区分 direct-only fallback 与 required failure；
- 两个模型 port、Resolver、Probe 与 Planner 的 closed decision 在 handler/freeze/model/mutation 前 fenced-CAS 为 immutable checkpoint；
- takeover 先 load checkpoint；命中时跳过 Semantic Router、Demand Interpreter、Probe 与 Planner。CAS loser 必须 reload winner；
- plan 之后才冻结 exact version/revision snapshot；freeze outcome 必须回显 TurnKey、plan identity 与 execution entry 的完整 binding；
- Required freeze 不提供 partial API；Optional Composite 的 direct-only entry 只能调用独立 `freezeDirectOnly`，不能把 Required freeze 结果删字段后复用。

## session-continuity: Restore Model Context Across Restarts

Blocked by: context-envelope, conversation-identity-foundation, turn-execution-control
Status: resolved
Type: Prototype

### Question

应持久化 ADK session，还是让每轮从 MySQL Context Envelope 重建模型上下文？

### Answer

已选择从 durable canonical Conversation 与 pinned ContextReadSet 重建模型上下文，不把 ADK session 持久化为历史 authority。V2 的 runtime session 每次模型调用 fresh 创建，前端继续使用 canonical `conversationReference`/`TurnKey` 和 status/replay contract；legacy HTTP 路径仍在 session 被替换时回写新的 `sessionId`，不会把 ephemeral V2 session id 当作下一轮的 scope。

- `MySqlTurnContextAdapter` 只读取 `message_sequence <= attempt.contextMessageHighWater` 的 committed canonical messages，并限制 recent turns；`ConversationContextSummary` 从 pinned high-water 与 recent turns 纯函数重建，固定 digest、最新 turn 截断长度和 summary 上限。
- existing `ContextReadSet` 仍由 fenced-CAS 固定 membership、summary、Profile 与 exact Memory recall pins；takeover 先按 checkpoint/read-set 重建，revoked/失效 pin 继续 fail closed。查询结果先复制为可逆列表，避免恢复时因不可变 JDBC 返回值导致反转失败。
- `DefaultTurnRouteComputer` 为 Router 与 Demand projection 绑定同一 server-owned `TurnKey + ContextReadSet digest`，各自保留 projection digest；`TurnClassificationService` 和两个模型 adapter 对未绑定、跨 turn 或跨 read-set 输入直接拒绝。
- `ModelInputBinding` schema 1 将 TurnKey、read-set digest、model input digest、rendered-input digest 与 canonical rendered input 组成 envelope。`ToolFreeChatModelInvoker` 每次用 fresh tool-free session，runtime 自动 history、tool state 和 primary branch session 不会流入下一 turn。
- Plain generation/response 同样从 attempt/read-set 派生 binding。重启/接管的 contract test 验证相同 pinned facts 产生相同 summary/envelope，fresh session id 变化不会改变实际模型输入；完整真实进程重启 E2E 留作部署环境验收，不在本票据内引入运行中事件流恢复。
- 本票据无需数据库 schema 变更：复用已有 canonical message、`context_message_high_water` 与 durable read-set checkpoint，因此没有新增或执行 migration。

本票据只解决模型 Context 重建，不承担运行中事件流恢复。活动 turn 的 v1 恢复合同由 `turn-execution-control` 提供：RUNNING 查询、terminal replay，以及过期租约接管。

## conversation-scope-migration: Move Consumers To Canonical Scope

Blocked by: conversation-identity-foundation, context-envelope
Status: resolved
Type: Task

### Question

M3 如何迁移旧 source/upload/lifecycle/files/message scope，同时保持历史对象可读和 owner-fenced？

### Answer

已完成。`MySqlConversationScopeKeyResolver` 以 owner + diagram + ACTIVE canonical binding 为权威，返回 canonical id 加最多 8 个 immutable legacy alias；raw alias、跨 owner、归档/删除会话和超界 alias 都 fail closed。新写入统一使用 canonical key。

source/retrieval、upload、lifecycle、material files、conversation messages、Context attachments 与 recent-message summary 已接入同一 resolver：历史数据 canonical/alias 双读，新数据 canonical 写入；`session_id` 保留为兼容审计字段，消息 `conversation_id` 在 active diagram 上回填。

`2026-07-27-migrate-conversation-scopes.sql` 已加入并在本地 MySQL 容器执行：55 个 active owner+diagram 默认 conversation、57 个无歧义 alias，active diagram 的消息无未回填 `conversation_id`；deleted diagram 历史消息继续受 deleted fence 保护。migration 不改写 `material_scope_link` 或 snapshot identity。

补充了 resolver 的 alias/owner/上限测试、migration contract test，并验证 repository 4/4、infrastructure scope regression 15/15、resolver 4/4、application reactor compile 通过。

## chartbook-profile: Add Real Project Context To Chartbook

Blocked by: context-envelope, session-continuity
Status: resolved
Type: Prototype

### Question

Chartbook 除 shared files 外还应拥有哪些可编辑、可版本化的 Project Context？

### Answer

已完成首版 Profile vertical slice，Project Context 的边界固定为：

- `instructions`、`goal`、`summary`、`glossary`、`defaultStyle`、`stableConstraints`；working decisions 留给后续 Memory，diagram relationships 不混入 Profile；
- `chartbook_profile` current row、`chartbook_profile_version` immutable history 和 `chartbook_profile_audit` idempotency ledger 独立于 shared files 与 legacy preferences；已有 Chartbook 以 version 0 EMPTY Profile 回填；
- API 为 owner-fenced `GET/PATCH /api/v1/chartbooks/{chartbookId}/profile`，PATCH 使用 `If-Match` optimistic CAS 与 `Idempotency-Key`，stale write 返回 `CATALOG_CONFLICT`，archived Chartbook 只读；
- Profile 版本 pin/digest 进入 ContextReadSet，materializer 只投影有界的 Profile 字段到 Router/Plain Drawer；Profile 行损坏只将该 slice 标记为 degraded，不把普通 source-free drawing 变成 source failure；Evidence/source body 不进入 Profile；
- 验证：domain、owner/CAS/idempotency adapter、Context pin/materialize、migration contract 共 12 项测试通过；full application reactor compile 通过；本机 MySQL migration 已执行并核对 `profile=1`、`version=1`、`audit=0`、`missing=0`。

## optional-enrichment: Make OPTIONAL Actually Optional

Blocked by: source-execution-plan
Status: resolved
Type: Prototype

### Question

可选资料增强在 resolution、timeout、no-match、insufficient evidence 或依赖故障时如何安全降级？

### Answer

OPTIONAL 降级边界已按 closed algebra 落到 application contract：

- `SourceProbeCommand.from(SourcePlanningRequired)` 是唯一 Probe command factory；Required command 在类型上没有 fallback，Optional Discovery command 则在第一次 source I/O 前携带确定性 `ValidatedPlainFallback` branch id、Plain plan 与原始 planning lineage。
- `SourceProbeOutcome` 只回显 binding 与 metadata candidate refs，不决定策略；`OptionalEnrichmentPlanner` 先校验 lineage/context/input binding。Optional no-match、timeout、dependency unavailable 只生成独立的 `ProbeFallbackReady`，不会伪装成零 source-call 的 `SourceFreeReady`；命中才生成携带同一 signed branch 的 `OptionalRetrievalDrawPlan`。
- Required source 的 no-match、timeout、dependency unavailable 全部 `PlanningBlocked`；authorization/terminal、cancellation 与 capability binding mismatch 对 Optional 也 fail closed。没有合法 Plain draw branch 的 Optional Discovery 在 Pre-Planner 阶段即拒绝，不允许调用 Probe。
- 执行期 fallback taxonomy 只接受 snapshot dependency unavailable、evidence insufficient、retrieval dependency failure 与 pre-commit citation rejection，并且只能读取最终 plan 的 `validatedFallback()`。
- `OptionalEnrichmentFallbackHandler` 在 fallback 前先 discard 并 close branch-local primary scope；任一步销毁失败都不会启动 fallback。销毁完成后才由既有 source-free `PlainDrawingHandler` 从 pinned `BaseTurnContext`/`ContextReadSet` 启动 fresh tool-free generation，Plain request 类型不含 Evidence、citation、candidate 或 source capability。
- fallback 只发送安全的 `enrichment_skipped` reason/branch receipt，然后进入正常 Plain progress/strong commit；不会发 provisional answer、candidate XML、citation 或 source-used 声明。

新增 planner/handler contract tests 覆盖 Probe hit/no-match/timeout/dependency、Required fail-closed、binding mismatch、terminal/cancel、四种 post-Probe fallback、scope 销毁顺序与销毁失败零 generation。完整 Maven reactor 通过（1704 项，0 failures，9 skipped）；本票没有新增或执行数据库 migration，production assignment 仍保持 legacy。

## direct-composite-hardening: Finish Direct And Composite Boundaries

Blocked by: source-execution-plan, optional-enrichment
Status: resolved
Type: Prototype

### Question

Direct、Direct + Retrieval 和 EDIT 场景还需要哪些明确合同？

### Answer

已把 Direct、Retrieval 与 Composite 从单一 attachment demand 拆为明确的 REQUIRED/OPTIONAL typed demand。所有 current-attachment demand 都必须通过 claim 保存的本轮 message binding；未随消息发送的 Conversation File 和历史图片不能成为 current-attachment referent。Direct v1 只允许 CREATE + REQUIRED，EDIT + Direct 在 Probe 前返回 `UNSUPPORTED_DIRECT_EDIT`。

`SourceProbeCommand` 现在携带 TurnKey、planning lineage、完整 declaration/context/input digest，并按 Direct、Retrieval、Optional Composite、Required Composite 分型。Probe outcome 使用 role-aware availability；每个 Direct/Retrieval candidate fact 都回显同一 binding，Direct candidate 还原子携带 origin、observation fingerprint 与 clarification ref。角色交换、candidate binding 交换、terminal/cancel 和全局 Probe unavailable 均 fail closed。

`DirectCompositePlanner` 已实现：

- Direct 无候选返回 `DIRECT_SOURCE_MISSING`，多候选进入 `AMBIGUOUS_DIRECT_IMAGE` clarification；
- Required Composite 缺任一角色均停止；
- Optional Composite 只有在 Direct 已确定且 Retrieval reason 明确属于可降级集合时，才签发 package-issued `ValidatedDirectOnlyFallback`；
- primary 与 direct-only execution entry 共享不可重算的 `SourcePlanIdentity`，post-Probe fallback 只能消费原始 `BoundSourcePlan` 中已签名分支。

多图片 clarification 合同采用自然语言回复 + hidden clarification id；`ClarificationReplyResolutionPort` 要求 durable authority 逐值验证 TurnKey、option、set digest、candidate 与 expiry，不暴露 source mode 按钮。Direct、Grounded、Evidence Answer 分别只依赖固定的 `DirectVisionPort`、`DirectGenerationPort`、`GroundedGenerationPort`、`EvidenceAnswerGenerationPort`，application API 不接收动态 tool registry；Evidence Answer 固定 `aiKnowledgeAllowed=false`。

新增矩阵测试覆盖 Direct hit/no-match/ambiguity、Optional/Required Composite role availability、signed fallback、plan identity、terminal/global failure、swapped role/binding、EDIT + Direct、current-message binding 和 tool-free generation boundary。完整 Maven reactor 通过（1723 项，0 failures，9 skipped）；本票没有新增或执行数据库 migration，production assignment 仍保持 legacy。

本票只完成 source-aware planning/boundary contract。Direct/Grounded/Evidence Answer 的真实 strong commit、durable clarification row 写入、isolated VLM/S3 保存/导出/重开 smoke 由下一票 `source-aware-commit-seams` 完成；sync/stream outcome 的全量统一仍只在 M6 `unified-turn-delivery` 完成。

## source-aware-commit-seams: Implement M5 Strong Commits

Blocked by: direct-composite-hardening
Status: resolved
Type: Task

### Question

哪些 source-aware consistency adapters 必须在 M6 all-V2 前真实存在？

### Answer

M5 已通过 isolated all-path test executor 实现三条窄 strong seam；没有创建 production V2 assignment：

test executor 只在 isolated fixtures/smoke 环境复用真实 handler、port 与 adapter，不暴露 serving route，也不写 production assignment。

- `DirectTurnCommitPort` 原子保存 Canvas/version、opaque visual provenance、Direct source usage pin、assistant message 与 terminal，不产生 factual citation；
- `GroundedTurnCommitPort` 原子保存 Canvas/version、package-validated citations/evidence pins、assistant message 与 terminal；Composite 才附加 Direct provenance/pin；
- `EvidenceAnswerTurnCommitPort` 原子保存 answer message、package-validated claims/citations/evidence pins 与 terminal，不修改 Canvas。

三条 handler 只依赖固定的 path-specific generation/commit port；Evidence Answer request 继续由类型固定 `aiKnowledgeAllowed=false`。`ValidatedCitationManifest` 只有在每条 factual target 通过 support guard、且所有 evidence id 都属于 prepared snapshot whitelist 后才能签发；insufficient/unsupported/out-of-snapshot 均 fail closed。

`turn_source_execution_binding` 在 generation 前 first-writer pin exact plan identity、snapshot ref/digest 与 execution entry。三条 MySQL adapter 在短事务开头锁定并逐值验证 active attempt/lease、expected Canvas/target version、plan identity 与 snapshot binding；terminal replay 使用统一 decoder，未知 schema typed unavailable。

clarification terminal commit 现在同事务保存 stable hidden id、immutable option set/digest、exact candidate fact、DB-clock expiry、安全 assistant message 与 terminal；reply resolution 必须逐值匹配 owner/conversation/turn/id/option/set/expiry，authority store 故障 typed unavailable。

20260730 additive migration、ordered release manifest、immutable migration image 与 CI build/publish 已接入；本机 MySQL 已通过 predecessor/checksum gate 执行 release，并验证第二次运行幂等跳过。真实 schema-backed Direct strong transaction 1/1 通过并整体回滚测试 fixture；完整 clean Maven reactor 1766 项通过、0 failures/errors、10 skipped。

逐写点 fault-injection 覆盖 Direct、Grounded、Evidence Answer 与 durable clarification，terminal decoder unknown schema 也 fail closed。isolated executor 覆盖三条 handler，不暴露 production serving route；production assignment 仍保持 legacy，M6 canary 继续由 `source-aware-boundary-evals` 与 `unified-turn-delivery` 控制。

## memory-v1: Implement Confirmed Chartbook Memory

Blocked by: memory-policy, context-envelope, chartbook-profile, session-continuity
Status: resolved
Type: Prototype

### Question

如何先实现一个可控、可解释、不会污染 Evidence 的最小 Memory？

### Answer

已完成 Memory v1 的最小可控闭环：

- `MemoryProposalService` 只接受显式 `RememberDecisionDeclaration`；声明 digest 必须与 owner-fixed declaration 一致，模型执行阶段不能新增 remember 意图。
- `MemoryPolicySanitizer` 在 persistence 前拒绝 secret、PII、external fact/source ref、Profile-owned field、非法 key/stage 和超限文本；拒绝结果只有 safe code，不带原文或内容 digest。
- `MySqlConfirmedMemoryAdapter` 在 proposal transaction 内锁定并校验 `turn_execution.status = COMPLETED`、Chartbook owner 和 Diagram scope，再保存 versioned `PENDING` candidate；候选状态为 `PENDING | MATERIALIZED | EXPIRED | REVOKED`，并保存 policy version、TTL、retention 和 scrub 时间。
- proposal 以 owner + Chartbook + TurnKey + declaration digest fence 幂等；materialize 使用 `SELECT ... FOR UPDATE`，terminal replay、duplicate retry、过期、revoke/delete race 均不会复活已清除 payload，失败返回 typed `Gone`/`Rejected`。
- materialize 将内容原子转入 `chartbook_memory` 后清除 candidate payload；管理端提供 list、edit、disable、delete，并用 expected version 做 owner-fenced CAS。
- `MySqlTurnContextAdapter` 只读取同一 Chartbook 的 ACTIVE confirmed Memory，建立 exact memory pin，冲突/损坏时整片 degraded；投影仍是 `ConfirmedMemoryContext`，不会进入 source、Evidence、citation 或 retrieval scope。
- 新增 `2026-07-31-create-confirmed-memory.sql`、ordered release manifest、immutable `Dockerfile.20260731` 和 CI image build/publish；本地 MySQL 已通过 20260730 predecessor/checksum gate 执行，第二次运行按 checksum 幂等跳过。
- 全量 clean Maven reactor 1773 项通过、0 failures/errors、11 skipped；Memory sanitizer、Context projection、migration contract 和真实 MySQL candidate/materialize/scrub 测试均通过。

v1 仍只承载 `CONFIRMED_DECISION`；术语、style、stable constraints 继续归 Chartbook Profile，自动 extraction 和 Memory→Profile promotion 保留给后续专用 ticket。

## memory-automation: Add Extraction And Consolidation Carefully

Blocked by: memory-v1
Status: resolved
Type: Prototype

### Question

何时才适合增加自动 extraction、去重、合并、冲突和用户偏好 Memory？

### Answer

已完成一个不接生产路由的 shadow-only application prototype：

- `MemoryShadowTurnCommitted` 只接收已完成 turn、同一 Chartbook 和 bounded extractor output；没有 source、Profile、Memory recall 或隐式 remember declaration 入口。
- `MemoryExtractionShadowService` 先复用 Memory v1 的 secret/PII/external-fact/text policy，再检查 decision key 的 Profile ownership；拒绝只返回 safe reason code，不返回正文。
- 同一 decision key + applicability stage 下，相同规范化文本标记 `DUPLICATE`，不同文本全部标记 `CONFLICT`，不静默合并；过期 post-turn event 统一标记 `MEMORY_SHADOW_STALE_EVENT`。
- 输出只有 in-memory `MemoryShadowReport` 和 precision/conflict/rejection/stale 计数，没有 `MemoryCandidateStorePort` 依赖，不创建 candidate、不写 `chartbook_memory`、不进入 Context，也不自动 confirmed。
- 新增 5 个 application tests，覆盖安全候选/重复、冲突、敏感与 Profile 字段、stale event；application 模块全测 174/174 通过。

后续若要生产化，仍需独立的 `TurnCommitted` outbox、匿名化/权限化 shadow sink、confirm/reject/precision 指标和用户确认入口；这些不在本票内。

## ux-receipts: Make Context Use Visible Without Technical Modes

Blocked by: source-policy-adr, source-execution-plan, memory-policy
Status: resolved
Type: Prototype

### Question

如何让用户知道本轮使用了普通提示词、附件、Chartbook 资料或 Memory，同时不暴露 Direct/RAG 技术术语？

### Answer

已完成前端语义化 receipt prototype，并接入 agent 消息的 route snapshot：

- `context-receipts.ts` 定义 standalone/Chartbook、当前附件、Chartbook 资料、已确认决策、资料引用和可选增强跳过的统一 receipt contract；用户可见文案不暴露 `DIRECT`、`RETRIEVAL` 或 RAG 技术模式。
- `ContextReceiptBar` 以只读 chips 展示本轮上下文，预留资料、Memory 管理和引用入口 action；当前消息的附件/Library selection 与 route 后的实际 source use 在 agent message 上绑定快照，历史消息不会随当前 Chartbook 切换而改写。
- NONE、附件、资料、附件+资料、Memory unavailable/stale、zero citation 和 optional no-match 都有明确语义状态；Memory 不可用只显示提示，不阻断当前请求。
- 当前后端尚未发布 durable Memory receipt 或 optional-skip event，因此 UI 只消费已有 route/evidence 数据；后续 transport 可直接填充同一 contract，不需要恢复 per-message source checkbox 或技术 SourceMode 控件。
- 新增 receipt 定向测试 4/4；结合既有 agent presentation 测试共 25/25 通过；TypeScript 检查通过，ESLint 0 errors（页面仅有既存 3 条 warning）。

## turn-lifecycle-evals: Gate M1 Turn Control

Blocked by: turn-execution-control
Status: resolved
Type: Research

### Question

哪些测试是 M1 完成和 M2 接流的前置条件？

### Answer

已覆盖 application contract、canonical conversation reference resolver、admission gate、attachment binding、memory declaration conflict、orphan takeover/epoch fencing、本地 MySQL 回滚 smoke，以及 facade 层同 TurnKey 并发提交的单 claim/replay 测试。另已固定 `ORPHANED_RETRYABLE` 不能伪装成 product terminal；`AttemptLease` 现在携带同一 DB transaction 的 `databaseNow`、`expiresWithin`、`renewWithin`，claim/heartbeat/takeover 返回 monotonic `LeaseTimingAnchor` 所需的相对 lease 信息。已用本地 MySQL 事务验证 `claim → orphan → takeover → stale deadline fence loss → current deadline cancel`。M1 application facade 现在已把 typed conversation resolution、fingerprint/policy snapshot、assignment 与 claim outcome 串成单一入口；旧 HTTP/transport 仍保持 legacy，待 M2 translator 接线后补齐并发 transport 矩阵。

新增真实双连接 InnoDB smoke：并发 claim 得到 `Claimed + AlreadyRunning`，并发 terminal CAS 得到 `Committed + AlreadyTerminal`，最终各只保留一条 execution/message，且由首个提交者获胜。验证暴露并修复了 MySQL `REPEATABLE READ` 下普通 consistent read 继续使用旧 snapshot 的问题：取得 conversation 锁后必须改用 `SELECT ... FOR UPDATE` current read，才能观察并发提交的 execution 并避免重复 user message。最低矩阵：

- 同 turn 并发请求只能产生一个 claim、一个 user message 和一个可接受 terminal commit；
- lease 过期接管后旧 epoch 的 heartbeat、attempt-scoped cancel、fallback 和 commit 全部失败；
- initial claim/heartbeat/takeover 的 lease timestamps 全由数据库时钟产生；adapter 同时返回 relative safety budget，runner 从调用前的 monotonic start 计时；
- explicit cancel 取消当前 turn；server deadline 必须携带 attempt fence，旧 epoch timer 不能取消 takeover；
- disconnect 只 detach，status 最终可读 terminal；explicit cancel 与 commit race 只能产生一个 persisted terminal；
- 执行中切换 feature flag 不影响已 claim turn；retry/restart/takeover 复用相同 policy hash；
- existing assignment 按保存 schema 选择历史 fingerprint；unseen 用 current schema；
- attachment refs 保留用户顺序并拒绝重复；同 turn 重排必须 fingerprint conflict；
- cancel 在模型 port/Probe/Planner 阶段已由 cancellation port 持久化后，coordinator 返回 `TurnDecisionAlreadyTerminal`；不写 checkpoint，terminal commit adapter 调用数必须为零；
- checkpoint crash-before 可重算；crash-after/takeover 必须 load 同一 decision。两个并发首 attempt 的 CAS loser 只能 reload winner；
- trace 记录 assignment/claim、attempt epoch、lease/takeover、policy hash、input binding、decision digest、detach/cancel 和结果，不记录敏感正文。

本轮补齐 M2 接流前的 transport 并发证据：`TurnHttpDeliveryAdapter` 的 sync 与 submission-only 调用并发进入同一 `TurnDeliveryExecutor`，两次请求经 translator 生成完全相同的 `UserTurnCommand`，共享 durable claim boundary，并固定只能得到一个 `ExecutionAccepted` 与一个 `AlreadyRunning`；accepted/running 两者均映射为 HTTP 202。既有 canonical/legacy control、status/cancel、terminal replay 与 detach contract 一并回归，trigger turn 定向测试 20/20 通过；application/infrastructure 的 M1 回归此前已分别通过 114/114 与真实 MySQL 56/56。本票不接入 legacy controller 或生产 assignment，也没有新增或执行 migration。

## plain-boundary-evals: Gate The Source-Free V2 Path

Blocked by: source-free-v2-path, turn-lifecycle-evals
Status: resolved
Type: Research

### Question

哪些测试必须在 all-path V2 canary 前证明普通画图物理隔离成立？

### Answer

M2 source-free boundary 已收口，生产 assignment 仍保持 legacy：

- `PlainDrawingHandlerTest` 以参数化矩阵覆盖 CREATE/EDIT/LAYOUT；generation failure、write gate disabled 与每个动作都证明不会绕过一次 fenced commit；`PlainResponseHandlerTest` 覆盖 response failure 与 disabled gate；
- `PlainRuntimeRegistry` 只能暴露 `PLAIN_GENERATION`，`PlainBoundaryContractTest` 反射锁定 Plain handler 的构造器/字段不含 Source、Snapshot、Material、RAG、Evidence、Citation 或 Retrieval seam；两个 handler 拒绝 legacy/dynamic execution profile；
- `ChatPlainGenerationAdapterTest` 与 `ChatPlainResponseAdapterTest` 验证 fresh tool-free session、严格 JSON schema、非法模型输出 typed unavailable，以及当前消息附件永远不进入 Plain prompt；Profile/Memory 仅作为不可信 DATA 块投影，不能提供 source facts 或 retrieval tool；
- `TurnV2ExecutionCompositionConfigTest` 验证 Plain drawing/response 只有在对应 generation + commit ports 同时存在时才装配，且运行图仍与 legacy assignment 隔离；
- application reactor 全量测试 `121/121` 通过；M2 infrastructure adapter 及 composition 定向测试 `9/9` 通过，未新增或执行 migration。

本票只证明 source-free boundary，不打开 production cohort。M6 的 all-path canary 仍依赖 source-aware planning、clarification、commit 与 session continuity gates。

## source-aware-boundary-evals: Gate The M6 Source Cutover

Blocked by: source-execution-plan, optional-enrichment, direct-composite-hardening, source-aware-commit-seams, conversation-scope-migration, session-continuity
Status: resolved
Type: Research

### Question

哪些测试必须在 Direct/Retrieval/Composite 进入 all-V2 前通过？

### Answer

M6 source-aware boundary gate 已落成，并继续只在 isolated executor 中运行；production assignment 在 M6 canary 前保持全部 legacy。

- `SourceAwareBoundaryGateTest` 新增 15 个参数化/组合断言：三类 Direct origin 都保留完整 Probe binding；Direct 缺失时 Optional/Required Composite 都返回 typed Direct rejection，不退化为 Retrieval；Required Retrieval 缺失永远不产生 partial plan；Direct/Retrieval membership 都参与 plan fingerprint；Optional direct-only 只能使用 root plan 签发的 `SignedDirectOnly` branch 并保留同一 `SourcePlanIdentity`。
- 既有 planning gate 覆盖 selected/current attachment、Optional Discovery hit/no-match/processing/dependency、multi-image clarification、binding/role swap、Required fail-closed 和 tool-free generation boundary；`SourceAwareGenerationBoundaryTest`、planner 与 strong-handler 定向集合为 `58/58`。
- durable clarification authority 覆盖 hidden id、option/set digest、owner/conversation/turn binding、candidate binding、stale/expired 与 authority unavailable；source execution binding 和 Direct/Grounded/Evidence strong commit 的 infrastructure 回归为 `40` 通过、`1` 个真实 schema test 因未配置 MySQL 跳过。
- application full test 为 `189/189`，composition isolation 为 `5/5`。freeze/prepare/commit 必须继续逐值回显 TurnKey、plan identity、snapshot binding 与 execution entry；membership revision、restart/takeover high-water、PROJECT_AUTO/Personal Library scope 和 unified sync/stream delivery 由其各自 M3/M6 gate 继续阻塞 canary，不能以本票提前放行。

## context-memory-evals: Lock Later Context And Memory Boundaries

Blocked by: session-continuity, chartbook-profile, memory-v1, ux-receipts
Status: resolved
Type: Research

### Question

哪些后续测试保证 Context、Profile、Memory 与资料 Evidence 不会互相越权？

### Answer

Context/Memory boundary gate 已完成，durable read-set 是 takeover/restart 的唯一上下文 authority：

- `ContextReadSet` digest 现在有明确回归，覆盖 message high-water、Profile pin 和 confirmed Memory pin；`DefaultBaseTurnContextAssembler` 在已有 winner、CAS retry 和 revoked materialization 时不会重新读取 live candidate 或把 loser candidate 送入 Router。
- `MySqlTurnContextAdapter` 已补齐真实 adapter contract：Profile/Memory revision 或 digest 漂移统一 `Retry`；Malformed Profile、重复 decision key 或非法 Memory JSON 只把对应 optional slice 标为 `DegradedContext`，Canvas/Plain 不被阻断；跨 owner Chartbook 的 membership/Profile/Memory 均保持 absent。
- `BaseTurnContextBoundaryTest` 与 restricted demand projector 锁定：Profile/Memory 可作为 Router context data，但不能进入 Source Demand；degraded/stale/conflicting slices 不会被伪装成 live facts。Plain path 没有 Source/Material/Retrieval/Citation seam，Memory 也不能触发资料调用。
- Memory v1 的 sanitizer、owner/Chartbook scope、candidate lifecycle、payload scrub、version CAS、revoke/delete race 与 shadow-only extraction 已由既有 application/infrastructure tests 覆盖；自动 extraction 不写 candidate、不进入 Context、不自动 confirmed。
- 本轮验证：domain `302/302`、application `190/190`、infrastructure `271` 通过、`5` 个需要外部服务的测试跳过。没有新增或执行数据库 migration；生产 assignment 仍保持 legacy。session cache、PROJECT_AUTO scope 和 unified sync/stream delivery 仍由 M6 canary/cutover tickets 负责。

## delivery-plan: Convert Resolved Decisions Into Small Commits

Blocked by: docs-reconciliation, turn-lifecycle-evals, legacy-retirement, plain-boundary-evals, source-aware-boundary-evals, context-memory-evals, memory-automation
Status: resolved
Type: Task

### Question

如何把已解决的票据排成可独立验证、可回滚的实现切片？

### Answer

交付顺序已落地为“可独立回滚的实现 commit”与“只能按门禁激活的发布顺序”两层，未采用一次大重构：

1. H0/M0 先完成 superseding ADR、source-free containment 与 M1 review safety/release gates（`9b5fae3d`、`084cf3c5`、`a12a0567`）。
2. M1 按 control plane → transport lifecycle → single-instance startup 分片（`1b17043f`、`f771e1cc`、`2734904a`、`cbd592d2`）；canonical identity、claim/fence、attachment binding 和 terminal seams 各有独立测试与回滚点。
3. M2 先交付 isolated Plain generation/response/commit 与 source-free boundary（`dfdd373e`、`939714dc`、`bf617e8f`、`1c0ba263`、`a51473e8`），production assignment 继续保持 legacy。
4. M3–M5 依次落 Context pin/rebuild、canonical scope migration、Chartbook Profile、typed source planning、optional signed fallback、Direct/Composite 和 source-aware strong commits（`e48b9f02`、`70bb5e0a`、`931ab2bf`、`90052728`、`0a67cfb8`、`799d43b0`、`0d630810`）。每个 source-aware commit 只接自己的 typed seam。
5. M6–M9 的激活门禁按 `SourceAwareBoundary` → unified sync/stream outcome → stable `V2_CANARY` → guarded `ALL_V2` → retirement readiness 执行（`c2272719`、`51e718f3`、`c0196b5a`、`fdc15741`、`188848fe`）。
6. Profile/confirmed Memory/shadow automation 与 receipt/context boundary 作为独立可禁用切片发布（`5b15f336`、`2262d205`、`dbf3aefc`、`8d4b093b`），不会扩大 source scope 或改变 Plain path。

发布前每个切片必须通过对应 Maven/isolated evaluation、sync/stream contract、合法 flag 组合和部署 smoke；数据库变更使用 ordered release manifest 与 immutable migration image（已验证的 `release-20260729`、`release-20260730`、`release-20260731`），按 predecessor/checksum gate 顺序执行，重复运行必须幂等跳过。

激活顺序固定为：先保持旧 assignment 兼容 → stable canary → pause/drain 后 `V2_CANARY → ALL_V2` → 最终 scanner/readiness gate 后 `ALL_V2 → RETIRED`。M6 mode switch 前按 DB high-water mark backfill retry-eligible legacy assignment，并为不可安全恢复的旧 key 写 Gone tombstone；旧 key 继续返回 `410 LEGACY_RETRY_EXPIRED`，无旧证据的 key 才能作为 unseen V2。

回滚边界同样固定：`V2_CANARY` 可回到 `LEGACY`；禁止绕过 canary 直接 `LEGACY → ALL_V2`；`ALL_V2` 后不接受旧 assignment 创建路径；`RETIRED` 后禁止回到旧 mode，只能使用兼容当前 migration generation 的 artifact。每个阶段的状态、migration checksum、测试结果和 commit hash 均记录在本 Wayfinder 及对应 release manifest 中。
