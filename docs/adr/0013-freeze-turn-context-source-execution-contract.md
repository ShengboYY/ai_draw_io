---
status: accepted
supersedes:
  - "0007: turn sequencing, source demand and target/source orchestration portions only"
  - "0012: request source timing and AUTO scope portions only"
---

# 冻结 Turn Context、资料需求与执行合同

## 背景

FreeDraw 同时拥有普通画图、Direct 图片还原、资料 Retrieval、Evidence Answer 和 Chartbook。历史实现把 source snapshot 放在 Router 前，并由同步、流式入口分别编排 source 和 Evidence。这会让资料基础设施的故障扩大到普通画图，也会让两种 transport 的业务语义逐渐分叉。

本 ADR 冻结新的 turn/source 合同，作为 M1–M6 实现前的规范依据。它不声称所有目标能力已经生产可用；每项能力必须在对应迁移阶段通过测试和 feature gate 后才生效。

## 决策范围

本 ADR 只替代旧文档中与以下内容有关的顺序和语义：

- turn admission、claim、attempt fence、取消和断流语义；
- Router、source demand、requiredness、fallback 和 clarification 顺序；
- source availability probe、typed plan 和 snapshot 的时点；
- 普通画图的资料依赖边界；
- 同步与流式的共同业务结果。

以下内容继续由已有 ADR 约束：owner 与 scope authorization、lifecycle/retention、read lease、citation persistence、Evidence/Chunk 身份以及 Diagram target resolution。若旧文档与本 ADR 在上述冻结范围内冲突，以本 ADR 为准。

## 术语与事实归属

| 概念 | 权威事实源 | 允许的作用 |
| --- | --- | --- |
| Current Request | 当前已认证请求 | 本轮用户意图和声明 |
| Canvas Context | 服务端 Canvas store | 当前画布、版本、hash、selection |
| Conversation Log | 服务端 Conversation store | 原始消息和可重建历史 |
| Chartbook Profile | Versioned Profile store | instructions、goal、glossary、default style、stable constraints |
| Memory | Memory store | 同一 Chartbook 内用户确认的工作决策，`CONTEXT_ONLY` |
| Source Availability | Source Authorization | 无正文的可用性和相关性事实 |
| Source Snapshot | Snapshot store | 本轮 exact version/revision、scope、授权和 lease |
| Evidence | Retrieval/Evidence module | 从当前 snapshot 读取并验证的支持内容，可支持 citation |
| Runtime Session | Runtime adapter | 可丢弃的缓存，不是历史或项目上下文事实源 |

Conversation history、Profile、Memory、Snapshot 和 Evidence 不得互相替代。Memory 和 Profile 不得授权资料、触发 Retrieval 或支持 citation；Snapshot 只固定授权，不代表 claim 已被 Evidence 支持。

## Turn 执行合同

目标执行顺序固定为：

```text
sticky engine/policy assignment
→ atomic turn claim and message-attachment binding
→ server-owned BaseTurnContext
→ Semantic Router || restricted-input Demand Interpreter
→ deterministic Resolver
→ Pre-Planner
→ conditional Source Probe
→ typed Planner
→ source snapshot for source-aware plans only
→ path handler
→ fenced consistency commit
→ terminal outcome and ephemeral TurnEvents
```

### Admission、claim 与恢复

- 每个 unseen turn 先持久化 sticky engine/policy assignment。assignment 不读取 prompt、语言、附件内容、source availability 或模型输出。
- V2 turn 必须在 Router/LLM 前 atomic claim，并保存唯一 user message、当前消息附件 binding、request fingerprint、policy snapshot 和 context high-water。
- RUNNING attempt 使用 lease 和递增 epoch。只有当前 fenced attempt 可以 heartbeat、调用执行器、取消或提交。
- 旧 epoch 的 heartbeat、cancel、fallback 和 commit 必须失败关闭；接管后从持久化的 input binding、read set 和 checkpoint 重建。
- 同 turn 的 RUNNING 重试返回 `202 + status endpoint`。v1 不恢复历史进度事件；已持久化终态允许 replay。
- transport disconnect、writer failure 和 serialization failure 只 detach subscriber，不取消产品 turn。显式 cancel 或 server deadline 才能取消执行资源，并与终态 commit 做持久 CAS。

### Server-owned Base Context

Router、Drawer、Review、Evidence Answer 和后续 Memory 从同一个服务端 Base Context 派生各自 projection。Base Context 可以包含当前请求、可信 Canvas/selection、durable Conversation、Chartbook membership、Profile 和 confirmed Memory，但不包含 source body、Source Snapshot、Evidence chunks 或 citation。

前端提交的 `conversationMessages`、`canvasXml` 和旧 source fields 只能作为兼容声明或诊断输入，不能覆盖服务端事实。ADK/runtime session 只能作为 cache hint；重启、接管或 cache mismatch 必须从持久化事实重建相同的实际模型输入。

## Source Demand 与计划

Semantic Router 负责 action、target、follow-up、evidence need 和工具权限建议。restricted-input Demand Interpreter 只读取 current instruction、当前消息 attachment binding、Chartbook membership identity 和安全 clarification labels，不读取 Conversation history、Profile、Memory、availability 或 source body。

Demand proposal 必须带 current-instruction evidence span、input digest、referent、confidence、model version 和 policy version。Resolver 只做确定性校验，不做关键词解析、不扩大授权、不执行 Source I/O。

Resolver 的结果分为：

- `SourceFreeReady`：普通 CREATE、EDIT、LAYOUT、Review 或普通 Reply，不需要 source I/O；
- `SourcePlanningRequired`：语义明确且需要 Direct、Retrieval 或 Evidence 的本轮规划；
- `ClarificationRequired`：referent 不唯一、弱指代或 clarification reply 无法验证；
- typed unavailable/rejected：模型输出、绑定或 policy 校验失败。

只有 `SourcePlanningRequired` 才能调用 availability/relevance Probe。Probe 只返回无正文的 availability facts，并绑定 TurnKey、planning lineage、input binding 和 declaration digest。

Planner 必须生成 closed、typed 的 `TurnPlan`：

- `PlainDrawPlan` 不得依赖 Material、Snapshot、Retrieval、Evidence、Citation 或 source-aware adapter；
- Direct v1 只支持 CREATE，使用当前消息已持久化绑定的 exact image referent；历史 Conversation 图片不能伪装成本轮附件；
- Retrieval 区分 exact、Project AUTO、Required 和 Optional；
- Project AUTO 只包含 Conversation、Diagram、Chartbook，不包含 Personal Library；
- `Direct + Retrieval` 只能由 Planner 在两个 role 都有可验证事实时构造；Optional Retrieval 的 direct-only fallback 必须是 Planner 签发的分支；
- `EDIT + Direct` 不得静默降级为其他 source mode。

Source Snapshot 只能在最终 source-aware plan 之后冻结，并且必须绑定 TurnKey、plan identity、execution entry、membership revision/status、exact version/revision 和 authorization scope。Required snapshot 不允许 partial result；Optional fallback 不得声明使用过资料。

## Required、Optional 与失败语义

| 场景 | 资料依赖 | 失败结果 |
| --- | --- | --- |
| 普通自包含画图 | NONE | 继续 Plain；source 调用数为零 |
| Optional Discovery 无命中/不可用 | OPTIONAL | signed Plain fallback，并发送明确 receipt |
| Retrieval Required | REQUIRED | typed terminal；不进入未接地 Drawer |
| Direct required | REQUIRED | typed terminal 或 clarification；不静默换路径 |
| Evidence Answer | REQUIRED | strict grounded terminal；不足时 fail closed |
| Direct + Optional Retrieval | Direct required + Retrieval optional | 仅允许 Planner-signed direct-only fallback |

Evidence Answer 固定 `aiKnowledgeAllowed=false`。每个 factual claim 必须有当前 snapshot 内的 citation support；Memory、Profile、Conversation summary 和 Runtime session 不能支持外部事实。

## Commit 与 transport

Plain、Direct、Grounded Drawer 和 Evidence Answer 各自使用窄的 application consistency seam，在短事务内校验 attempt fence、expected Canvas/target version、plan identity、snapshot binding 和 terminal schema。

同步和流式接口必须调用同一个业务 executor，只替换 detachable `TurnEventSink`。唯一产品终态是持久化的 `TurnOutcome`；事件是临时 delivery receipt。任何 transport writer 错误不得产生第二次业务提交或伪造终态。

当前 grounded canvas + citation 原子提交继续保留。M0 不引入微服务、通用 workflow engine、分布式 saga 或第二套 Source God Module。

## 迁移状态矩阵

| 阶段 | 生产承接范围 | 规范状态 |
| --- | --- | --- |
| H0 | Legacy 普通画图止血 | 已完成：Router 先于 source resolve/freeze，普通路径零资料调用 |
| M0 | 合同与 ADR | 本 ADR；不改变生产行为 |
| M1 | Application module、canonical conversation、claim/fence、terminal seams | 待实现 |
| M2 | Isolated V2 Plain path、双模型路由和零 source 测试 | 待实现；生产继续 Legacy |
| M3 | Server-owned Context、as-of history 和 scope migration | 待实现 |
| M4–M5 | Typed planning、conditional Probe、Direct/Retrieval strong commits | 待实现；只允许 isolated test executor |
| M6 | All-path canary、统一 sync/stream outcome、ALL_V2 | 待实现 |
| M7–M8 | Chartbook Profile、confirmed Memory | 待实现 |
| M9 | Memory extraction shadow 和 Legacy retirement | 待实现 |

## 兼容与回滚

M1–M8 的 assignment 必须保存 engine、policy、schema/model version 和 hash。动态 flag 只影响新的 unseen assignment，不能改道已 claim 或已 assignment 的 turn。兼容字段只能缩小行为，不能扩大 source authorization。

每个阶段必须能独立发布、验证和回滚。M6 切换前保留 Legacy assignment 的 retry horizon 和 Gone tombstone；M9 在 inventory、retry horizon 和 tombstone retention gate 全部通过前不得删除 Legacy executor。

## M0 完成标准

- 本 ADR 成为 turn/source sequencing、requiredness、fallback 和 snapshot timing 的唯一规范；
- H0、M1–M9 的迁移边界和生产/isolated test boundary 已明确；
- 普通画图零 source、Evidence Answer strict grounding、Project AUTO scope 和 Direct v1 限制已冻结；
- M1 实现票据可以以本 ADR 作为前置依赖；
- 本阶段不新增生产运行代码，也不改变已有 source owner/scope、retention、citation 和 target resolution 语义。
