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
Status: in progress
Type: Task

### Question

如何保证 M2 前同一个 turn 不会因 legacy session alias 与新 conversation id 形成两个幂等 key？

### Answer

已开始实现 durable canonical conversation id、active actor/conversation/diagram binding 与 immutable legacy alias mapping。当前 canonical catalog、owner-fenced lookup/default creation 和 application TurnKey 已落地；Facade/HTTP translator 与历史 scope 双读仍留给后续接线。

Facade 在 fingerprint、assignment 和 claim 前解析 canonical `ConversationRef`。TurnKey、assignment、execution、clarification 与 tombstone 只保存 canonical id。

旧 alias 与新 id 的同 turn retry 必须命中同一行。无法唯一解析时 fail closed，不得临时把 runtime session 当 conversation scope。

本票只负责 identity foundation；recent history/summary rebuild 与 source/files consumers 的 scope migration留给 M3。

## turn-execution-control: Implement Claim, Fence, Status And Cancellation

Blocked by: conversation-identity-foundation
Status: in progress
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

## single-instance-migration-control: Build The Single-Instance Migration Boundary

Blocked by: turn-execution-control
Status: in progress
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

## all-path-v2-canary: Run A Sticky All-Path Canary

Blocked by: single-instance-migration-control, unified-turn-delivery, plain-boundary-evals, source-aware-boundary-evals
Status: open
Type: Task

### Question

如何在 all-V2 mode switch 前验证真实 production auth、commit 与 delivery wiring？

### Answer

Pending. 只有 singleton lock、admission drain 和 source gates 已验证时才启用 stable cohort。

eligibility 只使用 authenticated stable account/org key、allowlist 与 versioned rollout salt，不读取 prompt、source availability、Profile/Memory 或模型输出。

`assignOrReuse` 先解析 existing/tombstone。真正 unseen key 只按 stable cohort 得到整轮 V2 或整轮 legacy assignment。

canary 不区分 Plain 与 source-aware，也不读取 prompt、locale、附件或模型输出。sticky assignment 已保证同一 TurnKey 的重试不改道，因此不需要 admission grant reservation。

关闭 canary 只停止新 assignment。既有 V2 turn 继续由兼容 executor 完成。

测试覆盖 mode generation race、restart reconciliation 与 error-budget rollback。

## all-v2-cutover: Activate The M6 Boundary

Blocked by: all-path-v2-canary, session-continuity, conversation-scope-migration
Status: open
Type: Task

### Question

何时允许把所有真正 unseen turn 切到 V2？

### Answer

Pending. 只有全部 V2 path gates 通过后，才能 pause admission、drain 本地 legacy in-flight，并在 migration row transaction 中切到 `ALL_V2`。

mode switch 与 `assignOrReuse` 锁同一 migration row。generation changed 必须重建 command；恢复 admission 后 unseen routing 固定 `ALL_V2/ENFORCE`。

transaction 固定 retry horizon；旧 key/tombstone 继续返回 410，existing executable legacy 按保存 engine 服务到 expiry。

`ALL_V2` 后，不支持的新 legacy-only request shape 返回 typed `UPGRADE_REQUIRED`，不得为了兼容再创建 legacy assignment。

测试覆盖 pause/drain timeout、mode generation race、restart、旧 key、unseen key和 existing/canary assignment。

## unified-turn-delivery: Unify Sync And Stream Attempt Results

Blocked by: turn-lifecycle-evals, source-aware-commit-seams, source-aware-boundary-evals
Status: open
Type: Prototype

### Question

M6 全量 V2 前，如何证明 sync/stream 不再是两套业务执行？

### Answer

Pending. 两种 transport 必须调用同一 executor，只替换 detachable event sink。

只有 `PersistedTerminal` 写 final；ownership lost/self-abort/unavailable 转 status，disconnect 不取消。

测试覆盖每个 Plain、Direct、Grounded 与 Evidence Answer plan × sync/stream/detach/explicit-cancel/deadline-race 的 transport matrix。

writer failure、commit race 与 terminal decoder unavailable 均不得生成第二份 outcome，才能解除 all-V2 gate。

## legacy-retirement: Remove Legacy Execution Safely

Blocked by: all-v2-cutover
Status: open
Type: Task

### Question

何时可以在 M9 删除 legacy executor 与 migration ingress？

### Answer

Pending. batch scanner 使用 DB time，把到期 executable row 原子转 `EXPIRED_GONE` 并 upsert tombstone。

删除 executor 前必须同时满足 LEGACY inventory 归零、migration retry horizon 结束、tombstone retention/Gone 判定仍有效。

retry horizon 结束后再次 pause admission、drain 当时的本地 legacy in-flight、运行最终 scanner，并重验 executable inventory 为零。

通过后原子切到 `RETIRED`；restart 先 reconcile orphaned execution，再继续 expiry scanner。

retirement 发布永久禁用 `LEGACY/V2_CANARY` 新 assignment；旧配置启动 fail closed。

之后只能回滚到兼容当前 migration generation 的 artifact，不能创建已无 executor 的 assignment。

测试覆盖 scanner、restart、old-key retry 与 retirement inventory gate；无需 durable dispatch permit、lease 或 write fence。

## docs-reconciliation: Reconcile Superseded Source Semantics

Blocked by: formal-contract-adr
Status: open
Type: Task

### Question

哪些旧文档需要加 superseded 标记或改写，才能只保留一份来源链合同？

### Answer

Pending. 处理根目录 `CONTEXT.md`、2026-07-18 PRD、2026-07-19 Technical Design、2026-07-23 Completion Plan 和 2026-07-24 Source Chain Redesign 中冲突的段落。

旧文档仍可保留 owner/scope、lifecycle/retention、citation persistence 与 target resolution。

必须显式废止 Personal Library AUTO、per-message selection、旧 SourceMode、前置 snapshot、断流即取消和无 claim 的 turn sequencing。

## source-free-v2-path: Isolate The M2 Source-Free Path

Blocked by: formal-contract-adr, turn-execution-control
Status: in progress
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

## source-execution-plan: Build The Deterministic Source State Machine

Blocked by: source-free-v2-path, context-envelope
Status: open
Type: Prototype

### Question

如何把当前 `AUTO snapshot + Router fields + service branches` 收敛为单一确定性执行计划？

### Answer

Pending. 设计应包含：

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
Status: open
Type: Prototype

### Question

应持久化 ADK session，还是让每轮从 MySQL Context Envelope 重建模型上下文？

### Answer

Pending. 无论选择哪条路，都必须：

- 消除 UI 看得到历史但 Drawer 不记得的状态；
- 将替换后的 backend session id 返回前端；
- 限制 recent turns，并使用可重建、可失效的 summary 控制 token；
- claim 固定 server message-sequence high-water；initial attempt/takeover 只能读取该 as-of 边界前的 history/summary；
- Router 前 fenced-CAS immutable ContextReadSet，固定 membership、summary、Profile 与 exact Memory recall versions；
- takeover 按 pin 重建；普通 edit/supersede 保留旧 version，hard delete/revocation fail closed；
- 每次模型调用只接收 canonical rendered input、TurnKey、ContextReadSet digest 与 model-input schema；durable Conversation 始终是唯一历史 authority；
- runtime session 必须 stateless，或把 actor/conversation/high-water/read-set/input digest 全部纳入 cache key；任一 mismatch 都丢弃 cache 并返回新 session id；
- runtime 自动 history、tool state 与 primary branch session 不得进入下一 turn；重启/takeover 必须从同一 pinned facts 重建出相同实际 input digest；
- 用后端重启 E2E 验证普通追问、编辑和 Evidence Answer 连续性。

本票据只解决模型 Context 重建，不承担运行中事件流恢复。活动 turn 的 v1 恢复合同由 `turn-execution-control` 提供：RUNNING 查询、terminal replay，以及过期租约接管。

## conversation-scope-migration: Move Consumers To Canonical Scope

Blocked by: conversation-identity-foundation, context-envelope
Status: open
Type: Task

### Question

M3 如何迁移旧 source/upload/lifecycle/files/message scope，同时保持历史对象可读和 owner-fenced？

### Answer

Pending. `ConversationScopeKeyResolver` 对旧数据双读 legacy alias 与 canonical id，对新数据只写 canonical key。

source、upload、lifecycle、files、message 与 summary consumers 必须逐个迁移，不能各自把 runtime session 当长期 scope。

resolver 从 authenticated canonical binding 读取完整 bounded alias set，不接受 caller-supplied raw alias。

测试覆盖旧 alias/新 id 的同 turn replay、两个以上历史 alias 文件读取、跨 owner 拒绝、collision/too-many fail-closed 与重启恢复。

本票不批量重写 Material/snapshot identity；双读 horizon 结束前保留 resolver 与 audit。

## chartbook-profile: Add Real Project Context To Chartbook

Blocked by: context-envelope, session-continuity
Status: open
Type: Prototype

### Question

Chartbook 除 shared files 外还应拥有哪些可编辑、可版本化的 Project Context？

### Answer

Pending. 首版候选：

- instructions、goal、summary、glossary、default diagram style、stable constraints；
- working decisions 进入 Memory；diagram relationships 如需结构查询则使用后续独立模型；
- 独立于 shared files 的 API、版本、审计和删除语义；
- Router/Drawer 可见范围和 Evidence 不可见/可见边界。

## optional-enrichment: Make OPTIONAL Actually Optional

Blocked by: source-execution-plan
Status: open
Type: Prototype

### Question

可选资料增强在 resolution、timeout、no-match、insufficient evidence 或依赖故障时如何安全降级？

### Answer

Pending. 目标语义：

- 清空 Evidence/citation context，继续 ungrounded Drawer；
- 普通任务的 Optional Discovery 先用 relevance query 做 metadata/vector Probe；只有相关命中才构造 Retrieval plan；
- standalone Diagram 直接 `SourceFreeReady`；Chartbook Probe no-match/unavailable 使用 `ProbeFallbackReady`，两者 lineage 不得混淆；
- 纯 Optional Retrieval 的 Plain Probe fallback 必须在第一次 source I/O 前签发；Probe 后的执行期仍只能进入最终 Plan 中对应的 signed branch；
- grounded primary branch 使用 branch-local ephemeral runtime；fallback 从 pinned Base Context 与 signed branch 启动全新 tool-free invocation；
- primary branch 的模型/session/tool side effects 必须销毁，不能进入 fallback、Conversation 或下一 turn；
- pre-commit event 只允许安全进度，不得暴露 provisional answer、candidate、citation 或 source-used 声明；
- 发送 `enrichment_skipped` 或等价的明确 UI receipt；
- 绝不暗示使用了资料；
- REQUIRED、Direct required 和 Evidence Answer 继续 fail closed。

## direct-composite-hardening: Finish Direct And Composite Boundaries

Blocked by: source-execution-plan, optional-enrichment
Status: open
Type: Prototype

### Question

Direct、Direct + Retrieval 和 EDIT 场景还需要哪些明确合同？

### Answer

Pending. 至少覆盖：

- 请求携带 `currentTurnAttachments`；claim 原子写 message binding，旧 Conversation 图片不再伪装成新附件；
- 上传后未发送只有 Conversation File；只有随消息发送后才可成为 current-attachment Direct referent；
- EDIT + DIRECT 不再静默降级；
- Direct v1 只有 REQUIRED；Retrieval 拥有 REQUIRED/OPTIONAL policy；
- 多图片 clarification 使用自然语言回复 + hidden clarification id，不提交 candidate/source mode 按钮；
- Composite 的 role-aware Probe 只返回 `DirectRoleAvailable`、`RetrievalRoleAvailable` 或对应 `RoleUnavailable(reason)` 事实；
- 只有 Planner 将 Retrieval reason 映射为 Optional fallback-eligible 后，才能把 direct-only entry 签入最终 `BoundSourcePlan`；
- 全局 Probe 不可用、Direct unavailable、terminal 或 Required conflict 均停止；
- handler 必须 transport-agnostic；M5 保留现有 delivery 行为，并完成真实 VLM/S3 保存、导出、重开 smoke。

sync/stream outcome 的全量统一只在 M6 `unified-turn-delivery` 完成。

Direct 只注入 tool-free `DirectVisionPort` 与 `DirectGenerationPort`；Grounded/Evidence Answer 分别使用固定的 `GroundedGenerationPort`、`EvidenceAnswerGenerationPort`。

这些 port 的 application factory 不接收动态 tool registry。恶意图片/文档内容不能让模型取得 Source、Material、Retrieval、Memory-management 或任意跨路径工具。

## source-aware-commit-seams: Implement M5 Strong Commits

Blocked by: direct-composite-hardening
Status: open
Type: Task

### Question

哪些 source-aware consistency adapters 必须在 M6 all-V2 前真实存在？

### Answer

Pending. M5 通过 isolated all-path test executor 实现三条窄 strong seam；不创建任何 production V2 assignment：

test executor 只在 isolated fixtures/smoke 环境复用真实 handler、port 与 adapter，不暴露 serving route，也不写 production assignment。

- `DirectTurnCommitPort`：Canvas + direct provenance + assistant message + terminal；
- `GroundedTurnCommitPort`：Canvas + validated citations/source usage + assistant message + terminal；
- `EvidenceAnswerTurnCommitPort`：answer + claims + citations/source usage + assistant message + terminal。

Evidence Answer handler 固定 `aiKnowledgeAllowed=false`，只接受 Required evidence；sufficiency 不足必须 terminal，且每个 factual claim 都要通过 citation whitelist/support guard。

每条 adapter 都在一个短事务中验证 attempt fence、active lease、expected Canvas/target version、plan identity 与 snapshot binding。

fault-injection 覆盖每个写点和 terminal decoder schema；只能得到完整 persisted terminal、既有 terminal replay、fence lost 或 typed unavailable，不能留下半提交。

generation runtime 由 path-specific port 与 sealed execution profile 创建；handler 不接收通用 `AgentRuntimePort` 或可变工具集合。

## memory-v1: Implement Confirmed Chartbook Memory

Blocked by: memory-policy, context-envelope, chartbook-profile, session-continuity
Status: open
Type: Prototype

### Question

如何先实现一个可控、可解释、不会污染 Evidence 的最小 Memory？

### Answer

Pending. 实现边界：

- 只有明确的“记住这个决定”指令或 Memory 管理 API 才产生 deterministic `MemoryWriteDeclaration`；
- declaration 的 schema、rule 与 semantic digest 在 assignment/claim 固定；模型不能决定是否写 Memory；
- successful terminal 前由 `MemoryPolicySanitizer` 检查 secret/PII、external fact、Profile-owned field、kind 与大小；
- sanitizer 拒绝时只保存 safe reason；proposal、terminal 与 trace 都不得保存原文或内容 digest；
- 只有 sanitizer 产出的 bounded payload 才能与 successful terminal 原子保存为 versioned `MemoryCandidateProposal`；
- proposal 状态为 `PENDING | MATERIALIZED | EXPIRED | REVOKED`，并保存 policy version、`expires_at`、`retain_until` 与 `payload_deleted_at`；
- UI/独立 command 以 TurnKey + digest owner-fenced、幂等 materialize candidate，失败时显示待保存而非“已记住”；
- 覆盖 crash-before/after-terminal、terminal replay、candidate API retry 与 duplicate submission；
- 未 materialize proposal 使用短 TTL；materialize、reject、delete、Chartbook/account purge 后清除 payload，只保留无正文 digest tombstone；
- expired/revoked API 返回 typed Gone，status 标记不可重试；删除与 replay/materialize race 不能复活 payload；
- v1 仅 `CONFIRMED_DECISION`，术语/style/stable constraints 转 Profile update proposal；
- decision 提升为 Profile 时走专用 application promotion use case，以 expected Profile/Memory versions 在窄 integration transaction 中更新 Profile 并 supersede Memory；
- 普通 Profile/Memory module 不得互相访问 repository，promotion 冲突时两边都不写；
- 记录 decision key、适用阶段、supersedes、scope、canonical text、source turn/diagram、provenance、status 和 version；
- 仅在同一 Chartbook 的 Context Envelope 中按需注入；
- 用户可查看、编辑、删除和关闭。

## memory-automation: Add Extraction And Consolidation Carefully

Blocked by: memory-v1
Status: open
Type: Prototype

### Question

何时才适合增加自动 extraction、去重、合并、冲突和用户偏好 Memory？

### Answer

Pending. 必须先在 shadow mode 评估误记、冲突、敏感信息和 stale memory；自动结果不能直接升级为 confirmed。跨 Chartbook 用户画像/偏好不属于 v1，并需未来独立产品合同。

## ux-receipts: Make Context Use Visible Without Technical Modes

Blocked by: source-policy-adr, source-execution-plan, memory-policy
Status: open
Type: Prototype

### Question

如何让用户知道本轮使用了普通提示词、附件、Chartbook 资料或 Memory，同时不暴露 Direct/RAG 技术术语？

### Answer

Pending. 需要定义：

- standalone/Chartbook 位置和语义化 context indicator；
- 当前消息 attachment chips，以及执行后只读 source receipt chips；两者都不是 source mode 选择器；
- 执行后 source/memory receipt、可选增强跳过提示和引用入口；
- Memory 管理入口；
- 不恢复 per-message source checkbox 或技术 SourceMode 控件。

## turn-lifecycle-evals: Gate M1 Turn Control

Blocked by: turn-execution-control
Status: in progress
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

## plain-boundary-evals: Gate The Source-Free V2 Path

Blocked by: source-free-v2-path, turn-lifecycle-evals
Status: open
Type: Research

### Question

哪些测试必须在 all-path V2 canary 前证明普通画图物理隔离成立？

### Answer

Pending. 最低矩阵：

- Plain CREATE/EDIT/LAYOUT 对 Probe、Snapshot、Material、RAG、Evidence 和 citation ports 的调用数全为零；这些依赖故障时仍成功；
- Plain runtime registry 只含 `PlainGenerationPort` 的 tool-free profile；恶意 prompt/Profile/Memory 也不能触达 source 或 memory-management tools；
- 纯 style/layout、技术词和 Profile/Memory 文本不得误触 Retrieval；
- 只修改 Profile/Memory/history 时 restricted demand input digest 不变；renderer contract 禁止这些 slice 进入输入；
- 多语言标注集覆盖明确文本、否定表达、弱指代和普通任务的 Optional Discovery；FP/FN、澄清率与 discovery 误触发率是质量 gate；
- Resolver 穷举 evidence digest、message binding、referent、model/policy version 和 confidence threshold；
- 任一模型 port 输出非法时 typed unavailable，零 source/业务 mutation；
- Semantic Router 或 Demand Interpreter 任一超时/invalid 时，所有 path typed unavailable，且 source/业务 mutation 调用数为零；
- 文件只上传未发送不能成为 Direct；随消息发送后 binding 在 retry/restart/takeover 中稳定；
- “画用户登录流程”在有相关文档时提出 Optional Discovery；无 availability 时 SourceFree，Probe no-match/unavailable 时 signed fallback；
- poisoned/stale/wrong-conversation runtime session 必须被丢弃；并发 turn、restart/takeover 只从 pinned input 重建并得到相同实际 input digest；
- 旧 conversation alias 与 canonical id 命中同一 assignment/execution；并发 turn 的 context high-water 不读到未来消息；
- Plain mutation、assistant message 与 terminal fault-injection 不产生半提交。

该票只证明 source-free boundary，不单独打开 production cohort。M6 的 all-path canary 仍依赖所有 source-aware gates。

## source-aware-boundary-evals: Gate The M6 Source Cutover

Blocked by: source-execution-plan, optional-enrichment, direct-composite-hardening, source-aware-commit-seams, conversation-scope-migration, session-continuity
Status: open
Type: Research

### Question

哪些测试必须在 Direct/Retrieval/Composite 进入 all-V2 前通过？

### Answer

Pending. 最低矩阵：

- selected exact、PROJECT_AUTO、Direct、optional/required failures、multi-image ambiguity；
- Optional Discovery trigger/hit/no-match/unavailable 与 signed fallback；
- Direct candidates 在 Probe 前保持 non-empty set；每个 Probe fact 绑定 turn/lineage/declaration digest；
- Planner 只把 origin-specific candidate fact 映射成对应 sealed selector；freeze 只读 exact identity，不按 name/ref 重查；
- freeze 的 success/dependency-unavailable/membership-changed/terminal/cancel exhaustive matrix 都逐值回显 TurnKey、plan identity、execution entry；交换任一 binding 必须 fail closed；
- membership revision/status 漂移固定映射 `MEMBERSHIP_REVISION_CHANGED_RETRY`，Required/Optional 都不可 fallback或留下 partial snapshot；
- Required freeze 永远不返回 partial；Optional Composite direct-only 只能走 `freezeDirectOnly(SignedDirectOnlyEntry)`；
- multi-image clarification 由 `ClarificationReplyResolutionPort` 校验 hidden id、Router option、owner/candidate/expiry；覆盖 stale、cross-turn、cross-owner、expired 与 same-turn replay；
- 重复 submit 解析到同一 sticky assignment；cross-owner、TurnKey/action digest mismatch 与 replay after consume 按 typed contract 拒绝或重放，不能创建第二条 assignment；
- PROJECT_AUTO 覆盖 Conversation + Diagram + Chartbook，并验证 Personal Library 永远不可见；
- DIRECT × RETRIEVAL 的 role availability/fallback 全矩阵；
- 同一 planning lineage 穿过 Probe；Probe 后的 bound plan identity 原样穿过 Freeze、Prepare 与 Commit。
- 两个模型 port、Probe 与 Planner 覆盖 crash-before/after checkpoint、concurrent CAS winner 和 takeover；已 pin 时调用数都为零；
- Chartbook membership authority 故障按 scope 区分：独立 owner-fenced exact 可继续；Chartbook exact/AUTO 按 Optional fallback 或 Required terminal；
- Direct/Grounded/Evidence Answer 三个 strong adapter 逐写点 fault-injection，terminal schema unavailable 必须 typed fail closed。
- Evidence Answer 验证 `aiKnowledgeAllowed=false`、Required insufficiency fail closed，以及每条 claim 都有 snapshot 内 citation support；
- Grounded/Evidence initial attempt 与 takeover 使用相同 conversation high-water；重启后不得读到后到 turn 或改变 source plan。
- Direct/Grounded/Evidence runtime 只能使用各自 path-specific tool-free generation port；恶意文档/图片不得取得 Source、Material、Retrieval 或 Memory-management 工具。
- Optional primary 在 citation rejection、timeout 与 insufficiency 后 fallback 时，使用 fresh invocation；不得泄漏 primary session、candidate、answer、citation 或 pre-commit provisional event。

M5 只通过 isolated all-path test executor 收集这些结果；production assignment 在 M6 canary 前继续全部 legacy。

## context-memory-evals: Lock Later Context And Memory Boundaries

Blocked by: session-continuity, chartbook-profile, memory-v1, ux-receipts
Status: open
Type: Research

### Question

哪些后续测试保证 Context、Profile、Memory 与资料 Evidence 不会互相越权？

### Answer

Pending. 最低矩阵：

- 后端重启后对话连续；
- session cache key 绑定 actor/conversation/high-water/read-set/input digest；poisoned、stale、wrong-conversation cache 均丢弃并返回新 session id；
- concurrent turn、restart、takeover 与 runtime session replacement 都必须从 durable Conversation + pinned read set 产生相同实际 input digest；
- 同 Chartbook 跨图 instructions/memory 生效，跨 Chartbook/Owner 不泄漏；
- Memory unavailable 不阻断普通画图，stale/conflicting memory 可见；
- initial read 后修改 summary/Profile/Memory，再 takeover 仍使用同一 read set；hard revoke 不泄漏旧内容；
- Memory mid-recall failure 只能 diagnostic-only degraded，零条 Memory 进入 prompt；
- Context projector 只返回 conflict diagnostic，不做 observer I/O；post-terminal observer 故障时 Plain terminal 仍成功且不重试业务 commit；
- Profile/Memory 不能触发 Retrieval、扩大 scope 或支持 citation；
- 普通 turn 没有 declaration 时 proposal 写入数为零；同 TurnKey declaration mismatch conflict，retry/takeover 使用首次 pin；
- sanitizer 拒绝 secret/PII、external fact、Profile-owned field 与 oversized payload，且 DB/terminal/trace 都无原文或内容 digest；
- proposal 覆盖 PENDING→MATERIALIZED/EXPIRED/REVOKED、短 TTL、payload scrub、typed Gone、Chartbook/account purge 与 delete/replay/materialize race；
- Memory → Profile promotion 的成功、两个 version conflict、幂等重试与故障注入都不能产生双 truth；
- trace 只补充 slice status、counts、budgets 与结果，不记录 Context/Profile/Memory 正文。

## delivery-plan: Convert Resolved Decisions Into Small Commits

Blocked by: docs-reconciliation, turn-lifecycle-evals, legacy-retirement, plain-boundary-evals, source-aware-boundary-evals, context-memory-evals, memory-automation
Status: open
Type: Task

### Question

如何把已解决的票据排成可独立验证、可回滚的实现切片？

### Answer

Pending. 固定交付顺序：

0. H0：先把 legacy source resolve/freeze 移到 existing Router gate 后，恢复普通画图可用性。
1. M0：正式 superseding ADR、术语、状态机、Source Demand/Optional Discovery 规则与多语言标注集。
2. M1：application module、canonical conversation identity、sticky assignment、atomic claim/fencing、message attachment binding 与 terminal seams。
3. M2：双模型路由/Resolver 骨架、Plain strong commit、source-free 物理隔离与零资料调用测试；production 仍全部 legacy。
4. M3：durable Context/as-of history、consumer scope migration；M4–M5 完成 typed planning、conditional relevance Probe、Direct/Composite 与全部 strong commits。
5. M6：all-path gates 通过后启用 stable `V2_CANARY`；canary 通过后 pause/drain 并原子切换 `ALL_V2`，同时统一 sync/stream outcome。
6. M7–M9：Chartbook Profile、显式 confirmed Memory、自动 extraction shadow，以及三重 gate 后的 legacy retirement。

M6 mode switch 前按 high-water mark backfill retry-eligible legacy assignment，并为不可安全恢复的旧 key 写 Gone tombstone。

switch 后无 assignment 的旧请求返回 410；只有无旧证据的 key 才能作为 unseen V2。

M9 删除 legacy executor 前必须同时满足 inventory、migration retry horizon 与 tombstone retention gate。

每个切片必须包含 migration、合法 flag 组合、isolated eval/rollback、同步/流式测试和部署 smoke，不能以一次大重构交付。
