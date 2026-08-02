# Auto Memory 方案与开发日志

## 1. 结论

记忆收敛为三层：

1. **Session Memory**：当前会话最近消息与滚动摘要，短期、自动生成，不作为长期事实库。
2. **Chartbook Auto Memory**：当前 Chartbook 内有效的稳定偏好、反馈、项目约定与参考处理规则。
3. **User Auto Memory**：同一用户跨 Chartbook 有效的稳定偏好与反馈。

Canvas State、Chartbook Profile、RAG 继续作为独立上下文源，不并入 Memory。运行时不再存在
Confirmed Memory 或候选确认流程。MySQL 是长期记忆的权威存储；V1.4-E 已验证默认关闭的
Memory 向量影子检索、MySQL 权威回查和可复现随机 shadow。V1.5 增加默认关闭、仅供本地验证的
canary 模式；普通运行仍使用 SQL 候选，只有显式开启 canary 时才允许回查后的向量候选进入
Worker。

## 2. 边界与优先级

| 上下文源 | 负责内容 | 生命周期 |
| --- | --- | --- |
| Session Memory | 最近对话、会话摘要 | 会话内 |
| Canvas State | 当前画布事实和版本 | Diagram |
| Chartbook Profile | 用户直接配置的目标、术语、样式和稳定约束 | Chartbook |
| Chartbook Auto Memory | 项目范围内自动归纳的偏好、反馈、约定 | Chartbook 长期 |
| User Auto Memory | 跨项目自动归纳的稳定偏好、反馈 | User 长期 |
| RAG | 可追溯的外部资料证据 | Material/索引生命周期 |

冲突时采用以下顺序：

```text
本轮明确请求
  > 当前 Canvas
  > Chartbook Profile
  > 最近显式对话
  > Chartbook Auto Memory
  > User Auto Memory
  > 旧会话摘要
```

Auto Memory 是建议性上下文，不能覆盖当前请求、画布事实或 Profile。Chartbook 记忆比 User
记忆更具体，因此优先。

## 3. 统一领域模型

USER 和 CHARTBOOK 共用一组领域对象、端口和表，避免维护两套几乎相同的实现。

### 3.1 `memory_item`

| 字段 | 含义 |
| --- | --- |
| `scope_type` | `USER` 或 `CHARTBOOK` |
| `scope_key` | USER 使用 owner key；CHARTBOOK 使用 chartbook id |
| `memory_type` | `PREFERENCE`、`FEEDBACK`、`PROJECT`、`REFERENCE` |
| `semantic_key` | 同一作用域内的稳定决策维度键，不包含当前选择值 |
| `title` / `canonical_text` | 管理界面和上下文使用的规范内容 |
| `status` | `OBSERVED`、`ACTIVE`、`DISABLED`、`DELETED` |
| `confidence` | 合并后置信度 |
| `evidence_count` | 不同 Turn 的有效支持证据数 |
| `is_explicit` | 是否来自用户明确表达 |
| `version` | 管理 API 乐观锁和 Context pin 版本 |

唯一约束是 `(owner_key, scope_type, scope_key, semantic_key)`。所有查询同时携带 owner 与
scope，避免跨租户或跨 Chartbook 读取。

### 3.2 `memory_evidence`

Evidence 保存来源 Turn、观察类型、规范文本、置信度和 disposition，不复制整段原始会话。
同一 Memory、Turn 和观察摘要只计一次，使重试保持幂等。一次推断冲突只保留为
`CONFLICTING`；同一新值得到两个不同 Turn 的支持后才可替换非显式内容，显式内容仍只能由用户
编辑或显式同键观察替换。

### 3.3 `memory_extraction_work`

Turn 成功事务只写入轻量工作项。带 lease、version fence 和重试退避的 worker 在事务提交后
读取该 Turn 的规范用户消息，再调用独立、无工具的提取模型。这样模型延迟或失败不会回滚已经
成功的绘图 Turn，也不会在重试时重复计数。

## 4. 自动提取与状态推进

处理链为：

```text
Completed Turn
  → durable work item
  → Eligibility（仅跳过明确非记忆 Turn）
  → Extract（最多 4 条结构化观察）
  → Select（安全、作用域、Profile 边界）
  → Consolidate（语义键、证据、冲突、状态）
  → ACTIVE-only Context recall
```

状态规则：

```text
explicit observation  ───────────────> ACTIVE
inferred observation  ──1 turn──────> OBSERVED
OBSERVED              ──2 turns─────> ACTIVE
ACTIVE/OBSERVED       ──user action─> DISABLED
any managed item      ──user action─> deleted

inferred current A    ──1 turn says B──> keep A + CONFLICTING(B)
inferred current A    ──2 turns say B─> ACTIVE(B), old evidence SUPERSEDED
explicit current A    ──inference says B──> keep A regardless of count
```

- 严格的显式规则（例如“请记住这个决定……”）绕过模型推断并直接激活。
- 明确包含“所有画册 / across all chartbooks”等范围时，直接成为 USER Memory，即使当前
  Diagram 没有 Chartbook。
- 模型推断必须由两个不同 Turn 的一致证据激活。
- 新的自动观察不能绕过 `DISABLED` 状态；禁用是持久的用户 opt-out。
- 删除会物理删除 Item 与 Evidence。未来出现新的独立证据时允许重新创建同一语义键；如果用户
  不希望自动恢复，应使用禁用。
- secret、PII、URL/外部事实和 Profile 已负责的目标、术语、样式、约束等内容会被拒绝。

提取前置筛选只处理高确定性的低价值输入：纯问候/确认、纯记忆查询、一次性绘图创建和明确指向
当前对象的局部修改。出现“以后 / 总是 / 偏好 / 喜欢 / 不喜欢”等稳定信号时始终放行；不能明确
判断的输入也继续交给模型，避免为了省一次调用而漏掉真实偏好。显式“请记住”路径不经过筛选，
仍保持确定性直接激活。

模型提取时会在同一次调用中看到受限的现有候选（USER 与当前 CHARTBOOK 各最多 16 条）。候选
只包含作用域、类型、语义键、标题、规范文本和状态，不包含 owner、来源 Turn 或原始对话。同义且
同作用域且同值时复用候选的完整规范字段，新 Turn 只增加 Evidence；同一决策维度但选择值改变时
复用候选身份并保留新 `canonicalText` 作为 challenger；相关但不同维度或不同作用域时才创建新键。
`DISABLED` 也参与候选匹配，防止用户禁用的规则被换一个 key 自动恢复。

## 5. Context 召回

Context 只读取 ACTIVE Memory。SQL baseline 提供确定性默认值和向量故障回退；可选 semantic
链先由最多 3 个有界 facet 检索 CURRENT 投影，再一次性回到 MySQL 复核 owner、scope、状态、
版本与正文。普通 facet 使用 `0.80` 下限，原始请求或 facet 明确引用既有记忆时使用 `0.76`；
每个 facet 最多选择一个 winner，不同 facet 优先消费不同 `DecisionKey`，避免同一颜色 Memory
占据“颜色”和“标签”两个意图。Chartbook 同键规则仍覆盖 User 规则。

多意图 Planner V4 保留原语言，并为非英语 facet 附加简短英语等价表达，以适配当前
`multilingual-e5-large` 的跨语言分布；原始请求中的“我平时 / my usual”等显式记忆语义会传递
给拆分后的 facet，不能因为 Planner 精简措辞而丢失。向量投影只有同时通过 exact fetch 和 ANN
query 可见性检查后才标记 `COMPLETED`，因此不会把“已写入但尚不可检索”的记录提前暴露给召回。

最终选择分别构造最多 8 条 Chartbook 与 8 条 User 记忆，并受总条目和字符预算约束。SQL 查询
使用 `JSON_ARRAYAGG`，再在应用侧规范排序，避免 `GROUP_CONCAT` 长度截断和无序聚合导致 pin
摘要不稳定。候选读取与 materialization 会复核版本和内容摘要；首次执行把 Memory ID/version
写入 Context read-set，重试只 materialize 已 pin 集合，不重新召回或悄悄代入新版本。

Prompt 将 Chartbook 和 User 两组数据分别标识为不可执行的数据区。Semantic Router 和生成
模型不能把 Memory 当作工具权限、事实证据或高于本轮指令的命令。

## 6. 管理能力

管理 API 同时支持：

- `/api/v1/memory`：USER 范围；
- `/api/v1/chartbooks/{chartbookId}/memory`：CHARTBOOK 范围；
- list、edit、disable、activate、delete；
- `If-Match` version fence；
- owner/scope 双重隔离。

前端 Auto Memory 页面分为 “This Chartbook” 与 “Across all Chartbooks”，展示 ACTIVE、
OBSERVED、DISABLED、置信度、显式来源和证据数。用户可以编辑并激活、禁用、重新启用或删除，
不再处理候选确认。

## 7. 数据库和发布

迁移文件：

- `docs/sql/migrations/2026-08-14-create-auto-memory.sql`
- `deploy/aws/database/release-20260814.manifest`
- `deploy/aws/database/Dockerfile.20260814`
- `docs/sql/migrations/2026-08-15-add-auto-memory-aging-index.sql`
- `deploy/aws/database/release-20260815.manifest`
- `deploy/aws/database/Dockerfile.20260815`
- `docs/sql/migrations/2026-08-16-create-auto-memory-vector-outbox.sql`
- `deploy/aws/database/release-20260816.manifest`
- `deploy/aws/database/Dockerfile.20260816`

迁移创建统一三张表，并把旧 `chartbook_memory` 中 ACTIVE/DISABLED 内容迁入
CHARTBOOK/PROJECT Memory。旧表暂不删除，因为 MySQL DDL 不完全事务化，保留它们可用于数据
核对和开关回滚；旧候选/Confirmed Memory 运行时代码和前端流程已经删除。

发布顺序：

1. 保持 `AUTO_MEMORY_ENABLED=false` 部署兼容代码。
2. 运行 `20260814` 迁移并核对 schema history、表约束、回填数量和失败工作项。
3. 配置并验证 tool-free extractor agent `300030`。
4. 开启 `AUTO_MEMORY_ENABLED=true`，先观察 work backlog、重试率、拒绝率和激活质量。
5. 稳定后再安排独立的破坏性迁移删除旧表；不要与本次切换合并。
6. 单独执行 `20260815` 索引迁移；确认待清理分布后，才按第 10 节逐步开启候选老化。
7. 单独执行 `20260816` 投影 outbox 迁移，保持两个向量开关关闭；仅在独立 Memory namespace、
   分区密钥和 embedding 配置就绪后，先开启 projection，再开启只观测不接管 SQL 的 shadow。

开关关闭时不创建新工作、不调用提取模型、不暴露新管理 Controller，Context 回到旧
`chartbook_memory` 只读投影。完整产品回滚还需同步回滚前端，因为旧候选 API 不再由新后端
提供。

## 8. 向量数据库的边界

Memory Item 是结构化、带明确作用域和生命周期的数据。MySQL 始终负责权威 Item/Evidence、幂等
合并、用户禁用和乐观锁；向量库只允许成为可删除、可重建的候选检索投影，不能决定激活、冲突
晋升或删除，也不能绕过 owner/scope 过滤。

V1.4-B 仍让 Worker 使用现有 MySQL 有界查询。向量库只接收可重建的 CURRENT Item 与未解决
CONFLICTING Evidence；owner/scope 通过 HMAC 分区，metadata 不保存 title、canonical text、
Turn 或原始对话。内容只在生成 embedding 时发送给配置的推理端点。现有 Material RAG 仅复用
Pinecone transport，Memory 使用独立 namespace、metadata allowlist、投影模型和 durable outbox。

## 9. 验证策略

- 领域：作用域校验、安全过滤、显式直接激活、推断累计激活、冲突、幂等和禁用保护。
- Worker：显式绕过模型、明确非记忆 Turn 跳过模型、现有候选复用、无 Chartbook 的 USER 显式
  记忆、失败重试与 lease fence。
- Schema：表、检查约束、唯一键、外键、旧数据迁移语句和发布 manifest。
- Context：Chartbook/User 分层、ACTIVE-only、优先级、版本 pin、损坏数据单片降级。
- Composition：feature flag 关闭时无新模型/服务，开启时只组合一套端口与 worker。
- API/前端：无候选确认操作，两个作用域均可管理。
- 真实 MySQL：`MySqlAutoMemoryIntegrationTest` 仅在显式设置
  `AUTO_MEMORY_MYSQL_TEST_ENABLED=true` 时运行，并要求通过环境变量提供一次性数据库连接。
  测试直接执行正式 SQL，不维护另一套测试 schema。
- 提取质量：冻结样本位于
  `ai-agent-draw-io-infrastructure/src/test/resources/evals/auto-memory-v1/cohort.json`。样本与绘图
  Eval 共用“版本化 fixture”原则，但不扩张面向画布和工具调用的通用 Eval 领域模型。
- 候选归并质量：独立冻结样本位于
  `ai-agent-draw-io-infrastructure/src/test/resources/evals/auto-memory-consolidation-v1/cohort.json`，
  复用同一个正式 Prompt、解析器、DeepSeek 请求和报告骨架，只维护归并专用的判定指标。
- 冲突识别质量：V1.3 冻结样本位于
  `ai-agent-draw-io-infrastructure/src/test/resources/evals/auto-memory-conflict-v1/cohort.json`，继续复用
  归并评估器，只增加同维度换值的 `CHALLENGE` 指标。
- 候选检索质量：V1.4-A 冻结样本位于
  `ai-agent-draw-io-infrastructure/src/test/resources/evals/auto-memory-retrieval-v2/cohort.json`，覆盖
  中英文同义 challenger、否定语义、相关但不同维度、`DISABLED` opt-out、owner/scope 隔离及
  `SUPERSEDED/DELETED` 排除。向量实现必须通过相关召回、禁用召回和零泄漏门槛后才能替换 SQL。

### 9.1 Agent `300030` 校准门槛

`auto-memory-v1` 当前包含 15 个纯合成案例，Prompt contract 已升级为
`AUTO_MEMORY_EXTRACTION_V6`，覆盖中英文、USER/CHARTBOOK、无 Chartbook、
一次性任务、画布事实、Profile 字段、URL、secret、PII 和 Prompt Injection。离线测试保证：

- fixture schema、Prompt contract version 和风险切片不会静默漂移；
- 所有正例最多 4 条，并能通过正式 `AutoMemoryObservationService` 安全策略；
- 无 Chartbook 的正例只能使用 USER scope；
- unsafe 样本的期望结果必须为空；
- 提取适配器严格拒绝额外字段、不可用作用域和非规范 semantic key；
- User Turn 以 JSON 字符串进入 Prompt，不能通过伪造分隔符改变输入结构。

真实模型发布门槛为：

| 指标 | 门槛 |
| --- | --- |
| 正例 scope/type 准确率 | `>= 90%` |
| 负例排除率 | `100%` |
| unsafe 接受率 | `0%` |

每次真实校准必须记录 dataset version、Prompt contract version、模型精确版本和原始结构化输出。
同一配置至少重复三轮；任一 unsafe 样本产生 Memory 都直接阻止发布。离线门槛通过不等于模型
质量已通过，缺少模型凭证时不得把 Agent 标记为 calibrated。

本地 Agent `300030` 使用独立的 DeepSeek 配置链：

```text
AUTO_MEMORY_*（可选的能力级覆盖）
  → LLM_*_deepseek（共享 DeepSeek 连接）
  → https://api.deepseek.com + deepseek-v4-pro（非敏感默认值）
```

凭证只从 `.env`/部署环境读取，不写入 YAML 或 Git。当前先按 `deepseek-v4-pro` 运行 cohort；
由于提取任务短且结构化，后续应使用同一 cohort 对比 `deepseek-v4-flash`。只有 Pro 的正例准确率
或稳定性明显更好时才保留 Pro，不能仅凭通用模型能力承担额外成本和延迟。

固定请求参数为 `temperature=0` 和 `response_format=json_object`。这两个参数同时进入正式
Agent 与真实校准请求；正式解析器仍按 exact-fields schema fail closed，不能把 JSON 模式当作
业务 schema 校验的替代品。

### 9.2 DeepSeek V4 Pro 校准结果

2026-07-31 对 `auto-memory-v1` 的 15 个案例各运行三轮。第一次使用
`AUTO_MEMORY_EXTRACTION_V2` 和 provider 默认请求参数，未通过门禁：正例 scope/type
准确率 `77.78%`，负例排除率 `85.19%`，unsafe 接受率 `16.67%`，协议失败 2 次。失败集中在
FEEDBACK/PREFERENCE 边界、PII 排除和 JSON 漂移。

修订后的 `AUTO_MEMORY_EXTRACTION_V3` 明确“先排除、再选 scope、最后分类”，把个人联系信息
设为硬排除，并固定 JSON 模式和零温度。45 次调用结果：

| 指标 | V3 结果 | 门槛 |
| --- | ---: | ---: |
| 正例 scope/type 准确率 | `94.44%`（17/18） | `>= 90%` |
| 负例排除率 | `100%`（27/27） | `100%` |
| unsafe 接受率 | `0%`（0/12） | `0%` |
| 协议失败 | `0` | `0` |

唯一误差是一个中文 CHARTBOOK `PREFERENCE` 被分类成 `PROJECT`；作用域和内容仍正确。三轮共
使用 42,173 tokens，平均延迟 5,005 ms，P95 9,025 ms。报告不包含凭证或 reasoning：

- 首轮失败证据：`evaluation/auto-memory-v1/results/2026-07-31-deepseek-v4-pro-auto-memory-v1.json`
- V3 通过报告：
  `evaluation/auto-memory-v1/results/2026-07-31-deepseek-v4-pro-auto-memory-v1-v3.json`

### 9.3 V4/V5 归并与前置筛选验证

`AUTO_MEMORY_EXTRACTION_V4` 在 V3 安全合同上增加受限候选复用，输出 schema 不变，没有引入
第二次模型调用或额外 action DTO。2026-08-01 的本地持久化 MySQL + 浏览器真实链路结果：

- 第一条“所有项目节点标签不超过六个字并省略技术后缀”生成 USER/OBSERVED Item，Evidence=1；
- 同义改写复用完全相同的 `semanticKey` 和规范字段，数据库仍只有一行，Evidence=2 并自动进入
  ACTIVE；
- 纯问候“你好”的 work 正常完成，没有 DeepSeek 请求，也没有新增 Memory；
- 单元测试覆盖纯问候、记忆查询、一次性绘图和局部修改的跳过，以及持久信号和模糊输入的放行；
- MySQL 集成测试覆盖候选排序、owner 隔离和 `DISABLED` 候选可见性。

V3 的 45 次安全校准是历史发布证据，不能自动视为新 Prompt 已完成同等校准，因此本次继续按
9.1 的同一 cohort 重跑三轮并保存失败与通过报告。

第一次 V4 三轮校准把候选说明和空候选 JSON 注入所有请求。安全门槛仍通过，但正例只有
`77.78%`（14/18）：出现一次 FEEDBACK→PREFERENCE、一次中文 PREFERENCE→PROJECT，以及
两次中文跨画册偏好漏提取。失败报告保留为：

- `evaluation/auto-memory-v1/results/2026-08-01-deepseek-v4-pro-auto-memory-v1-v4-failed.json`

`AUTO_MEMORY_EXTRACTION_V5` 只在候选非空时注入归并合同和候选 JSON；没有候选时保持短分类
合同，既避免无关指令干扰首条 Memory 提取，也减少 token。V5 对同一 cohort 的 45 次调用结果：

| 指标 | V5 结果 | 门槛 |
| --- | ---: | ---: |
| 正例 scope/type 准确率 | `94.44%`（17/18） | `>= 90%` |
| 负例排除率 | `100%`（27/27） | `100%` |
| unsafe 接受率 | `0%`（0/12） | `0%` |
| 协议失败 | `0` | `0` |

唯一误差是中文、无 Chartbook 的跨画册偏好在一轮返回空数组；另外两轮正确。三轮共使用
44,109 tokens，平均延迟 5,645 ms，P95 12,207 ms。V5 已通过发布门槛；候选非空分支继续由
协议测试和真实本地同义归并链路覆盖。通过报告为：

- `evaluation/auto-memory-v1/results/2026-08-01-deepseek-v4-pro-auto-memory-v1-v5.json`

### 9.4 V1.1 候选归并校准

V1.1 没有修改生产数据库、召回查询或提取 Worker，而是补齐候选非空分支的独立质量门槛。冻结
cohort 包含 8 个纯合成案例，覆盖 ACTIVE/OBSERVED/DISABLED 精确复用、中英文同义改写、多个
候选择优、同作用域相关但不同规则、相同规则跨作用域、不安全候选数据，以及存在候选时仍应排除
的模糊输入。默认每例重复三轮，共 24 次真实调用。

门槛同时要求：复用准确率至少 `95%`；新建与空结果准确率 `100%`；错误归并、跨作用域归并、
DISABLED 绕过、不安全候选接受和协议失败全部为 `0`。CREATE 允许在不同作用域使用相同的
semantic key，因为持久化身份是 `scope + semanticKey`；只有复用了候选的完整作用域身份才算错误
归并。

2026-08-01 使用 `deepseek-v4-pro`、`temperature=0` 和 JSON mode 运行三轮，首轮实现即通过：

| 指标 | V1.1 结果 | 门槛 |
| --- | ---: | ---: |
| 候选精确复用 | `100%`（12/12） | `>= 95%` |
| 正确新建 | `100%`（9/9） | `100%` |
| 模糊输入排除 | `100%`（3/3） | `100%` |
| 错误归并 | `0%`（0/9） | `0%` |
| 跨作用域错误归并 | `0%`（0/3） | `0%` |
| DISABLED 绕过 | `0%`（0/3） | `0%` |
| 不安全候选接受 | `0%`（0/3） | `0%` |
| 协议失败 | `0` | `0` |

24 次调用共使用 30,696 tokens，平均延迟 6,431 ms，P95 9,136 ms。报告继续只保存合成输入对应
的最终结构化输出、usage 和聚合指标，不保存凭证或 provider reasoning：

- `evaluation/auto-memory-consolidation-v1/results/2026-08-01-deepseek-v4-pro-auto-memory-consolidation-v1.json`

### 9.5 V1.3 冲突演进校准

`AUTO_MEMORY_EXTRACTION_V6` 把 `semanticKey` 定义为“决策维度”而不是“当前选择值”。同作用域、
同维度、同值继续完整复用；同维度换值复用候选身份并输出新 `canonicalText`；相关但不同维度或
不同作用域仍新建。输出 schema 没有增加 action/relation 字段，运行时仍只有一次模型调用。

`auto-memory-conflict-v1` 包含 8 个合成案例，覆盖 USER/CHARTBOOK、ACTIVE/OBSERVED/DISABLED、
同值复用、同维度换值、相关但不同规则、相反值但不同 scope、危险候选和模糊输入。每例三轮，
最终 24 次调用结果：

| 指标 | V1.3 结果 | 门槛 |
| --- | ---: | ---: |
| challenger 识别 | `100%`（9/9） | `>= 95%` |
| 候选精确复用 | `100%`（3/3） | `100%` |
| 正确新建 | `100%`（9/9） | `100%` |
| 模糊输入排除 | `100%`（3/3） | `100%` |
| 错误/跨 scope 归并 | `0%` | `0%` |
| DISABLED 身份绕过 | `0%`（0/3） | `0%` |
| 不安全候选接受 | `0%`（0/3） | `0%` |
| 协议失败 | `0` | `0` |

DeepSeek 的 reasoning token 与最终 JSON 共用 `max_tokens`。`2048` 预算下业务判断正确，但一个
跨 scope CREATE 样本偶发在输出 JSON 前达到长度上限；门槛没有放宽，校准工具改为 `4096` 后
通过。最终报告使用 34,001 tokens，平均延迟 6,816 ms，P95 11,242 ms；失败报告保留用于区分
模型判断错误与输出预算截断：

- `evaluation/auto-memory-conflict-v1/results/2026-08-01-deepseek-v4-pro-auto-memory-conflict-v1-2048-failed.json`
- `evaluation/auto-memory-conflict-v1/results/2026-08-01-deepseek-v4-pro-auto-memory-conflict-v1.json`

V6 同时回归原有两个 cohort。基础提取仍为 `94.44% / 100% / 0%`，协议失败 0；V1.1 归并的
复用、新建和排除均为 `100%`，所有安全错误率及协议失败为 0：

- `evaluation/auto-memory-v1/results/2026-08-01-deepseek-v4-pro-auto-memory-v1-v6.json`
- `evaluation/auto-memory-consolidation-v1/results/2026-08-01-deepseek-v4-pro-auto-memory-consolidation-v1-v6.json`

## 10. V1.2 长期记忆老化

当前系统能看到 Memory 被提取、重复支持和注入 Context，但还没有可靠的“模型实际使用了这条
Memory”信号。因此 V1.2 不按时间降级 `ACTIVE`：长期未重复不代表偏好已经失效，也不使用
DeepSeek 判断是否删除。

老化只处理同时满足以下条件的弱候选：

```text
status = OBSERVED
AND is_explicit = false
AND updated_at < now - retention
```

- `ACTIVE`、所有显式 Memory 和 `DISABLED` 永不由该任务修改；用户管理仍是它们的唯一生命周期
  控制面。
- 默认 retention 为 90 天，代码拒绝低于 30 天；默认每小时最多清理 100 条，硬上限 1,000 条。
- 每次调度只执行一个有序、原子的 `DELETE ... LIMIT` 批次，不先读后删，避免与并发激活或用户
  编辑产生竞态。
- 删除未确认 Item 时 Evidence 由外键级联删除；未来出现新的稳定证据仍可重新学习。这里不写
  `DELETED` tombstone，因为候选从未进入 Context，也没有用户 opt-out 语义。
- `idx_memory_item_aging(status, is_explicit, updated_at, memory_id)` 只服务全局小批量维护；权威数据
  仍在 MySQL，不引入第二存储或向量索引。

配置保持独立且默认关闭：

| 环境变量 | 默认值 | 约束 |
| --- | ---: | --- |
| `AUTO_MEMORY_AGING_ENABLED` | `false` | 同时要求 `AUTO_MEMORY_ENABLED=true` 才组合任务 |
| `AUTO_MEMORY_OBSERVED_RETENTION_DAYS` | `90` | 至少 30 天 |
| `AUTO_MEMORY_AGING_DELAY_MS` | `3600000` | 固定延迟调度 |
| `AUTO_MEMORY_AGING_BATCH_SIZE` | `100` | 1–1,000 |

上线时先执行 `20260815`，保持老化关闭并用同一谓词统计 90 天前候选数量；确认数量和样本合理后
再开启开关。若需要回退，只关闭老化开关；索引可以保留，不影响提取、召回或管理路径。后续只有
建立可靠的召回使用、用户纠正或规则冲突信号后，才单独设计 ACTIVE 的复核机制，不能复用当前
时间清理策略。

## 11. V1.3 ACTIVE 冲突演进

V1.3 只解决已有 `semanticKey` 下的值变化，不扩展为通用语义图或多候选投票：

1. 模型返回与候选相同的 scope/key/type/title、不同的 `canonicalText`，Worker 保留新值作为
   challenger；相同值仍固定为服务端候选的规范文本。
2. 持久化先写 `CONFLICTING` Evidence。一个 Turn 不改变当前 Item；同一新值在两个不同 Turn
   出现后，才可替换非显式 Item。
3. 晋升在现有行锁事务内完成：旧 SUPPORTING 和其他 challenger 标为 `SUPERSEDED`，获胜
   challenger 的 Evidence 改为 `SUPPORTING`，同一 Item 更新为新值并保持 `ACTIVE`。
4. 当前 Item 只要 `is_explicit=true`，任意数量的推断冲突都不能替换它；`DISABLED` 仍在冲突
   判断前直接抑制。用户管理 edit/activate/disable/delete 的语义不变。
5. USER 与 CHARTBOOK 通过现有唯一身份隔离；跨 scope 的相反值不会互相挑战。

本次不增加表、状态、迁移、向量库、第二次模型调用或平行归并框架。challenger 暂按安全策略
规范化后的精确文本累计，不用 embedding 猜测两个不同改写是否相同；真实数据证明漏合并明显后，
再评估规范化策略。

严格“请记住……”仍走确定性显式路径并使用内容摘要键，保证不依赖模型即可直接激活。因此它
不会自动把任意新句子映射到旧决策维度；需要确定替换已有显式 Memory 时，当前可靠入口是管理
页面 edit，或未来单独设计用户可解释的显式目标选择，不能暗中复用推断模型。

## 12. V1.4-A 语义候选检索准备

V1.4-A 只建立下一阶段真正需要、且当前可以验证的最小边界：

1. `AutoMemoryConsolidationQuery` 统一携带当前 Turn、可选 Chartbook、用户文本和每 scope 上限，
   并由应用层固定允许访问的 USER 与当前 CHARTBOOK scope。
2. Worker 只依赖 `AutoMemoryConsolidationCandidateRetriever`；默认实现继续逐 scope 调用 MySQL，
   因此 Prompt、DeepSeek 调用次数和持久化行为不变。Spring 允许后续语义适配器显式替换默认实现。
3. 冻结 8 个纯合成 retrieval case，门槛为相关候选 `Recall@K >= 95%`、`DISABLED Recall@K = 100%`、
   未授权候选率 `0%`、终态候选率 `0%`。V2 明确让 Chartbook 场景同时授权 USER 与当前
   CHARTBOOK，与生产 Query 合同一致；其他 Chartbook 和 owner 仍是未授权干扰项。隔离和
   lifecycle 是硬门禁，不能拿相关度作权衡。

本阶段没有定义未使用的通用向量抽象，也没有把 Material RAG 投影强行复用到 Memory。

## 13. V1.4-B 可重建投影与影子检索

V1.4-B 实现投影和观测闭环，但不改变记忆归并结果：

1. Item/Evidence 每次有效变化都在同一 MySQL 事务中推进 `memory_vector_projection_work` 的
   `desired_revision`；重复 Evidence 不产生无意义投影。删除没有外键级联 outbox，使 tombstone
   仍能删除已经发布的向量。
2. Worker 对一个有 lease 的权威快照生成 CURRENT 和去重后的未解决 CONFLICTING 文档，先
   upsert 并确认可见，再删除旧 manifest 中不再需要的向量。投影期间若权威数据再次变化，完成
   时的 revision fence 会拒绝旧快照并重新排队；过期 worker 的迟到写入还会触发一次权威快照
   reconcile，避免旧结果最终覆盖新状态。
3. Pinecone 查询在 `topK` 前强制匹配 HMAC owner、允许的 USER/当前 CHARTBOOK scope 和候选
   lifecycle。`DISABLED` 也进入 shadow 检索，用来评估 opt-out 是否能被同义表达正确命中；
   `SUPERSEDED/DELETED` 不会被投影。
4. shadow 装饰器始终先取 SQL 权威候选，向量调用成功或失败都原样返回 SQL，仅记录无内容的
   成功率、SQL 候选数和向量命中数。Pinecone 暂时不可用不会影响 DeepSeek 提取或 Memory 写入。
5. `AUTO_MEMORY_VECTOR_PROJECTION_ENABLED` 与 `AUTO_MEMORY_VECTOR_SHADOW_ENABLED` 默认均为
   `false`。仅开启 shadow 而未开启 projection 时仍使用普通 SQL 路径，避免半配置状态改变行为。

V1.4-C 在这一投影边界上补齐权威回查和可比较的 shadow 指标，见下一节。

## 14. V1.4-C MySQL 权威回查与 shadow 对照

V1.4-C 只完成语义命中的安全闭环，不让向量候选参与 DeepSeek 输入：

1. Pinecone 返回的 ID 一律视为不可信输入，只接受本投影合同生成的 CURRENT 或 CHALLENGER
   格式；格式、Base64、Memory ID 或 challenger 摘要不合法时直接丢弃。
2. 合法 ID 只用于一次有界的 MySQL 批量回查。查询重新校验 owner、当前 USER/CHARTBOOK scope
   以及 Item 的 `OBSERVED/ACTIVE/DISABLED` 状态；向量 metadata 不能替代授权和 lifecycle。
3. CHALLENGER 只有在对应 `CONFLICTING` Evidence 仍未解决时才有效，并折叠为其父 Item 的当前
   候选。这样 Prompt 只看到稳定的 semantic key 和 MySQL 当前值，不会把冲突文本误当成权威；
   challenger 晋升或被取代后，旧向量 ID 即使尚未清理也会回查失败。
4. 回查保持向量排序，按 Memory 去重并重新执行每 scope 上限。shadow 仍无条件返回原 SQL
   候选，只增加原始命中数、有效回查数及与 SQL 重合数三个无内容指标。
5. 测试集中在会改变安全或正确性的边界：不可信向量 ID 拒绝、SQL 结果不受 shadow 成败影响，
   以及真实 MySQL 的 owner/scope、`DISABLED` 和 challenger 生命周期。没有增加模拟余弦相似度或重复
   happy-path 测试，因为它们不能证明真实 embedding 与 Pinecone 的召回质量。

真实 Pinecone cohort 门槛已经通过，执行边界和结果见下一节；真实业务 Turn 的 shadow 分布仍需
单独采集。

## 15. V1.4-D 真实 Pinecone 检索门槛

V1.4-D 增加一个显式 opt-in 的 live evaluation，不添加模拟 embedding：

1. 测试直接使用生产 `PineconeAutoMemoryVectorStoreAdapter` 生成 passage/query embedding、写入
   metadata 并执行 owner/scope/lifecycle 过滤。namespace 必须显式包含 `test`、`dev` 或 `eval`，
   否则拒绝运行。
2. 每个 case 使用随机隔离的 owner/scope 分区；测试还直接写入 `SUPERSEDED/DELETED` stale
   vector，验证即使清理延迟也不会被搜索返回。所有向量均使用本次 run ID，`finally` 删除并确认
   已清理。
3. fetch 可见不等于 ANN 可检索。正式评分前逐个用生产过滤器确认所有 eligible candidate 已进入
   检索面，避免把 Pinecone 最终一致性瞬态误算为排序质量。
4. 硬门槛仍只判断候选召回、`DISABLED`、授权和 lifecycle；Top-1 与 MRR 作为诊断指标记录，
   不让向量层越权承担 DeepSeek 的最终归并职责。

`multilingual-e5-large`、1024 维的真实结果为：相关 `Recall@K = 100%`，禁用候选召回为
`100%`，Top-1 为 `100%`，MRR 为 `1.0`，未授权、终态和未知向量返回均为 `0`。8 个 case 的
最终目标都排第 1，单次查询延迟约 515–741 ms。报告位于：

- `evaluation/auto-memory-retrieval-v2/results/2026-08-02-pinecone-multilingual-e5-large.json`

这证明冻结合成分布已通过，不等于真实业务分布已经通过。下一步的受控随机 shadow 基线见下节。

## 16. V1.4-E 可复现随机 shadow 基线

V1.4-E 在本地 MySQL 8.4 和隔离 Pinecone namespace 中运行生产 shadow 装饰器，不调用
DeepSeek，也不改变 Worker feature flag：

1. 复用 V2 检索 cohort 的 8 个中英文语义主题，按固定 seed 随机分配 USER/CHARTBOOK scope、
   Turn 顺序和自然语言前缀。正确目标是显式真值，不依赖模型自评。
2. 每个授权 scope 写入 10 条更新更近的干扰 Memory，SQL 每 scope 只取最近 8 条；正确目标故意
   设为旧记录，用于观察语义检索能否补回 SQL 时间窗口之外的候选。
3. 每个目标还在其他 Chartbook 和其他 owner 写入同文本副本，检查 Pinecone metadata 过滤是否
   在 MySQL 回查前已经阻止越权结果。模拟记录和向量都带随机 run ID，并在 `finally` 清理。
4. shadow 直接运行 `ShadowAutoMemoryConsolidationCandidateRetriever`。每轮必须原样返回 SQL，
   Pinecone 结果只用于统计真实 query embedding、向量命中和 MySQL 权威回查。

seed `20260802` 的 32 个 Turn 结果：SQL 真值召回 `0%`（由上述压力场景刻意造成），向量真值
召回 `100%`；Top-1 为 `37.5%`，全部目标均位于前 4。向量回查率 `97.85%`，其余结果因每 scope
上限被正常裁剪；向量候选与 SQL 候选重合率 `62.67%`。shadow 成功率和 SQL 返回保持率均为
`100%`，原始跨 scope/owner 命中及未知向量均为 `0`。报告位于：

- `evaluation/auto-memory-retrieval-v2/results/2026-08-02-random-shadow-seed-20260802.json`

这仍是有真值的受控压力分布，不能冒充真实用户流量。本地实际 Turn 的长期采样仍然缺失，因此
不能据此批准生产流量；V1.5 只允许开发环境显式开启 canary 来继续积累证据。

## 17. V1.5 本地向量 canary

V1.5 不等待不足的生产样本，但也不把 V1.4-E 的合成结果解释成生产发布证据。实现保持一个很小的
可逆边界：

1. 默认仍为 SQL-only。只有 `AUTO_MEMORY_ENABLED=true`、
   `AUTO_MEMORY_VECTOR_PROJECTION_ENABLED=true` 和
   `AUTO_MEMORY_VECTOR_CANARY_ENABLED=true` 同时成立时，canary 才替换默认候选读取器；新开关
   默认为 `false`，本阶段不修改生产配置。
2. 每轮仍先读取 SQL 权威候选，再从 Pinecone 取最多 16 个 ID，并经过既有 MySQL owner、scope
   和 lifecycle 回查。向量 metadata 或返回顺序不能绕过权威校验。
3. 有效向量候选优先进入列表，SQL 去重后补满，全局仍最多 32 条。固定给向量一半候选预算，既能
   验证 SQL 时间窗口外的语义召回，也保留 SQL 基线，不提前引入未经数据证明的调参系统。
4. Pinecone 搜索或 MySQL 回查出现运行时异常时，原样返回本轮 SQL 候选；SQL 本身失败仍正常暴露，
   不被 canary 吞掉。该路径不增加 DeepSeek 调用次数，只改变一次既有提取调用看到的候选集合。
5. `shadow` 与 `canary` 同时设置时由 canary 明确优先，避免创建两个候选读取器；生产启用条件仍需
   本地真实 Turn 样本证明召回收益、错误归并没有恶化，并另行获得发布授权。
6. 本地端到端验收使用 schema-only 隔离数据库、独立 Pinecone namespace 和纯合成账号。18 条
   合成 Memory 中，目标被故意放在 SQL 最近 16 条窗口之外；真实 UI Turn 经
   Turn → DeepSeek V4 Pro → consolidation 后准确复用该目标，Memory 总数保持 18，证明候选来自
   向量命中后的 MySQL 权威回查，而不是 SQL 时间窗口或新建重复项。
7. 将 Pinecone endpoint 临时指向不可连接的本地端口后，第二个真实 Turn 的 extraction 仍为
   `COMPLETED`，同一目标 Evidence 从 1 增至 2、Item 总数不变；投影任务同时保留明确的向量连接
   错误，证明 SQL fallback 生效而不是向量请求意外成功。验收后 18 个向量 tombstone 全部完成、
   manifest 归零，隔离数据库已删除，本地 projection/canary 开关恢复为关闭。

V1.4 的真实业务分布验证没有被标记为完成，只是从 V1.5 代码实现的前置条件改为后续生产门禁。

## 18. V1.7 请求相关的 Memory Context 选择

V1.7 暂时跳过完整 V1.6 质量观测，不增加新的 Memory 层、模型调用或数据库表，只收敛主绘图
Prompt 的 ACTIVE Memory 选择边界：

1. `AutoMemoryContextSelector` 统一接收当前 Turn 文本、owner 和可选 Chartbook。默认按既有 MySQL
   `is_explicit → confidence → updated_at → memory_id` 顺序，每个授权 scope 有界读取 16 条；不再
   通过 Turn domain SQL 无界聚合全部 ACTIVE Memory 后依赖未定义的数组顺序取前 8 条。
2. `AUTO_MEMORY_CONTEXT_SEMANTIC_ENABLED=true` 只有和 vector projection 同时开启时才复用现有
   Pinecone。查询在 provider `topK` 前限定 `CURRENT + ACTIVE`、owner 以及 USER/当前 CHARTBOOK，
   返回 ID 仍必须经 MySQL 重新校验；向量失败时使用同一 SQL baseline，不影响 Turn 可用性。
3. 未开启 semantic 时继续使用 SQL；向量技术失败也回退 SQL。semantic 成功时按 provider score
   显式降序，只保留达到 `AUTO_MEMORY_CONTEXT_MINIMUM_SCORE` 的结果，允许选择 0 条且不再用 SQL
   补满。只有相同 Memory type + `semanticKey` 时，当前 CHARTBOOK 覆盖 USER 全局值；最终每 scope
   最多 8 条、总计默认最多 12 条和 6,000 个 Prompt 字符。
4. `AutoMemoryContext` 保存真实选择顺序，Prompt renderer 和 Semantic Router 使用同一投影。
   Context read-set 升级为 schema v2，在既有 JSON 中记录选中 `memoryId + version` 并纳入摘要；
   materialize 只回查这批身份，向量不重复执行，未选中 Memory 的并发变化也不会让本 Turn 漂移。
   schema v1 read-set 仍可解码，不需要 DDL 迁移。
5. semantic 开关默认关闭；即使不开向量，确定性排序、跨 scope 同键覆盖、总预算和精确 pin 仍然
   生效。V1.7 不新增 usage 写入、学习排序、DeepSeek 调用或内容日志；`last_recalled_at` 等反馈在
   真实数据证明需要后再用独立统计边界设计，不能污染 `memory_item.version`。

本地 MySQL 集成验证覆盖 CURRENT/ACTIVE 命中、challenger 拒绝、owner 隔离和 DISABLED 失效；
完整后端测试共执行 2,031 项，0 failure、0 error，21 项按既有 live/integration 开关跳过。

### V1.7.1 score-aware 稀疏选择实验

Pinecone match score 现在作为瞬时排序证据返回但不持久化；Memory adapter 不信任 provider 数组
顺序，显式按 score 降序。独立 development cohort 用于校准单一 `0.827` cutoff；首次冻结 V2
holdout 不参与调参。development 达到 8/8 Top 1、MRR 1.0，平均选择 1.875 条；V2 的 8 个目标也
全部 raw Top 1，但两个目标分数低于 cutoff，最终 Recall@1/3/12 均为 75%、MRR 0.75、无关率
40%、平均选择 1.25 条。V2 因 Recall@12 未达到预注册 87.5% 门槛而失败，不能降低阈值后重跑。
semantic 开关继续默认及本地关闭；若继续优化，必须用新 development cohort 设计非绝对拒绝规则，
再使用未见 V3 holdout 验收。

### V1.7.2 有界置信度准入

V1.7.2 在提交 `22ce4202` 先冻结互不重叠的 development-v2 与 V3，且两者都增加“没有相关
Memory，应返回空”的负例。准入仍先要求第一名达到 `0.82`；若第二名也达到该分数，则保留全部
过线候选，避免把相近的正确候选强制裁成一条；否则第一名必须至少领先第二名 `0.02`。不满足时
返回空，不用 SQL 补位。该规则只增加一个相对分差参数，不引入查询分类器、reranker、DeepSeek
调用、持久化分数或针对单 Case 的作用域分支。

development-v2 的 8 个正例与 8 个负例达到 Recall@1 75%、Recall@3/12 87.5%、MRR 0.8125、
无关选择率 12.5%，负例误注入率 0；SQL baseline 的目标 Recall 为 0、负例误注入率 100%。唯一
漏召回的低分 Chartbook 正例没有触发特殊修补。V3 的门槛和数据 SHA 已在首次运行前预注册于
`evaluation/auto-memory-context-v3/README.md`。唯一一次 V3 达到 Recall@1/3/12 87.5%、MRR
0.875、负例误注入率 0，但 3/10 的无关选择率为 30%，超过预注册上限 25%，因此未通过。失败来自
查询已接受后保留了全部过线候选，而不是负例拒绝；V3 没有被用于修改参数或重跑。

### V1.7.3 Top-1 相对候选窗口

V1.7.3 保留 V1.7.2 的查询级 `minimumScore=0.82` 与 `minimumLead=0.02`，只拆出候选级边界：
通过准入后，候选既要达到绝对下限，也不能比 Top-1 低超过 `0.03`。窗口内若仍有超过 4 条近似
同分候选，则整组返回空，不通过截断伪装确定性。该上限高于 development-v3 已标注的双目标请求，
没有把系统写死成 Top-1/Top-2，也没有增加模型、持久化字段或第二套排序服务。

development-v3 在实现前与 V4 一同冻结，包含 6 个单目标、2 个双目标和 8 个负例。原“全部过线”
基线 Recall@12 为 70%、无关率 58.8%、负例误注入率 12.5%；只取 Top-1 将 Recall@12 降到
60%。`0.03` 窗口加宽簇拒绝保持 Recall@12 70%，同时把无关率降到 12.5%、负例误注入率降到
0，平均选择从 1.0625 降到 0.5。两个仍漏召回的正例在候选裁剪前已被既有查询准入拒绝，因此不在
本阶段追加阈值补丁。V4 数据、门槛与隔离规则预注册于 `evaluation/auto-memory-context-v4/`。

唯一一次 V4 结果证明候选裁剪方向有效但整体尚不能发布：被选中的 6 条全部相关，无关率与 8 个
负例误注入率均为 0，Chartbook 覆盖和权限门槛通过；但 10 个目标只召回 6 个，Recall@3/12 为
60%，未达到 80%/90% 门槛。四个漏召回分别来自跨语言目标 raw rank 16、双目标第二条超出相对
窗口，以及旧查询准入整组拒绝。没有按 V4 扩大窗口或调整分数后重跑；下一阶段应单独验证多意图
查询拆分，而不是继续叠加全局阈值。

### V1.8 有界多意图召回规划

V1.8 不修改 V1.7.3 的全局分数、分差和候选窗口。它在共享
`AutoMemoryContextSelector` 前增加可选的 `AutoMemoryRecallPlanner`：

1. 一个宽松、低成本的前置信号只跳过明显单意图请求；出现 `and`、`同时`、分号等并列信号时，
   独立 tool-free Agent `300031` 才使用 `deepseek-v4-pro` 判断是否确有多个可独立召回的意图。
   连接词本身不直接切句，名词并列由模型返回空计划。
2. Planner 严格返回 0、2 或 3 个自包含子查询；原始请求始终保留为最后一个语义 fallback。
   子查询先执行，使每个意图独立应用既有 `0.82 / 0.02 / 0.03` 准入规则，避免一个意图的高分
   候选挤掉另一个意图。
3. 多次向量命中的 ID 按子查询顺序去重后，只进行一次 MySQL 权威回查；之后继续使用既有
   Chartbook 同键覆盖、总条目/字符预算和 Context read-set pin，不增加数据库字段或平行排序器。
4. Planner 失败退回原始语义查询；单个向量子查询失败不丢弃其他成功意图。只有所有语义查询均
   技术失败或 MySQL 权威回查失败时，才使用确定性 SQL baseline。
5. `AUTO_MEMORY_CONTEXT_MULTI_INTENT_ENABLED` 独立默认关闭，且只在 semantic selection 已开启时
   生效。Planner 与提取 Agent 使用不同 ID 和合同，但复用相同 DeepSeek 连接配置，避免两种 JSON
   协议相互污染。

development 和未见 holdout 已在提交 `2e8bf4ee` 冻结。首次 development 运行的 10 个合成
Case 全部通过：Case accuracy、多意图覆盖和单意图 abstention 均为 100%，协议失败为 0；没有据此
修改 Prompt。报告位于：

- `evaluation/auto-memory-context-v5/results/2026-08-02-development-deepseek-v4-pro.json`

实现提交 `966fa5dc` 后，唯一一次 V1 holdout 只达到 Case accuracy 80%、多意图覆盖 66.67%、
单意图 abstention 100% 和协议失败 0，没有通过门槛。两次漏拆分别是不同目标属性的 `while`
表达，以及负向/正向组合；报告保留于：

- `evaluation/auto-memory-context-v5/results/2026-08-02-holdout-deepseek-v4-pro.json`

V1 holdout 已退役且不会重跑。V1.8.1 的 development-v2 与未见 V2 holdout 在修改前另行冻结；
只允许澄清“可能映射到不同 semanticKey 的目标/属性才是独立意图”并扩充模型调用信号，不增加
确定性硬拆规则，也不修改向量门槛。`AUTO_MEMORY_RECALL_PLANNING_V2` 的 development-v2 首次
运行 10/10 通过，Case accuracy、多意图覆盖和单意图 abstention 均为 100%，协议失败为 0；
唯一一次 V2 holdout 达到 Case accuracy 90%、多意图覆盖 100%、单意图 abstention 75%、协议
失败 0，因一条 `black but not gray borders` 被拆成正向/负向两个查询而未通过 abstention 门槛。
V2 不会重跑，V1.8 到此停止继续围绕小型合成集调 Prompt。跨语言的单目标低排名问题仍不属于
V1.8。

### V1.8.2 facet-aware 候选召回

完整 Planner → Pinecone → MySQL → Prompt 的 V1 holdout 表明，多意图目标可能已经进入向量前几名，
但原有查询级准入会整组拒绝，或把同一请求的其他意图挤出最终 Prompt。V1.8.2 没有继续下调全局
`0.82`，而是把 planned facet 明确建模为候选阶段：

1. Planner 正常返回 2～3 个 facet 时分别搜索；Planner 返回空或协议/供应商失败时，只有原请求存在
   分号、全角分号或换行，才保守拆成 2～3 段。普通连接词不做硬切分，取消信号也不会被 fallback
   吞掉。
2. 每个 facet 最多保留 4 个 `score >= 0.80` 的候选 ID；所有 ID 合并后只做一次 MySQL 权威回查，
   未授权、禁用、终态或失效 vector 不能进入最终选择。
3. 每个 facet 最多贡献一个 winner。MySQL 仍有效且属于不同 `semanticKey` 的前两名若领先差小于
   `0.01`，该 facet 放弃注入；相同决策维度的当前 Chartbook Memory 仍覆盖 USER Memory。
4. 只有所有 facet 都没有候选时，原始请求才使用既有严格 `0.82 / 0.02 / 0.03` 路径；没有新增
   DeepSeek rerank、数据库字段或平行排序服务。semantic 与 multi-intent 开关继续默认关闭。

development-v2 与一次性 holdout 在实现前冻结于提交 `f31ed6e8`，并新增候选 Recall@4 指标，以区分
“向量没有找回”和“候选找回后筛选错误”。锁定实现的 development-v2 达到候选 Recall@4 100%、
最终目标 Recall 80%、完整正例 Case 83.33%、Prompt Precision 88.89%、无关注入 11.11%、负例误
注入 25%、Prompt parity 100%；未授权、禁用和 forbidden 选择均为 0。`0.01` 保持为通用保守边界，
没有根据两条近似并列分数继续微调小数。完整报告位于：

- `evaluation/auto-memory-context-e2e-v2/development-report.json`

实现与 development 证据在提交 `89f1f2e8` 锁定后，V2 holdout 只运行一次并通过：候选 Recall@4
100%、最终目标 Recall 90%、完整正例 Case 83.33%、Prompt Precision 90%、无关注入 10%、负例
误注入 25%、Prompt parity 100%，未授权、禁用和 forbidden 选择均为 0。一条正确候选因不同决策
仅相差 `0.0019` 被保守放弃，一条一次性局部操作注入了位置 Memory；两项均按预注册门槛保留，未
修改参数或重跑。完整报告位于：

- `evaluation/auto-memory-context-e2e-v2/holdout-report.json`

## 19. 开发日志

### 2026-07-31

- [x] 创建并切换分支 `codex/auto-memory-layering`。
- [x] 核对 Session、Profile、Canvas、旧 Confirmed Memory 和 Turn 终态事务边界。
- [x] 建立统一 Auto Memory 领域模型、状态策略和安全过滤。
- [x] 创建 MySQL Item/Evidence/Work schema、旧 ACTIVE/DISABLED 数据回填和发布清单。
- [x] 接入 durable post-commit extraction、tool-free 模型、lease/retry 和 consolidation。
- [x] 接入 USER/CHARTBOOK ACTIVE Memory 的 Context pin、召回与 Prompt 分层。
- [x] 接入 owner/scope/version-fenced 管理 API。
- [x] 前端改为两个作用域的自动记忆管理，并移除候选确认交互。
- [x] 删除旧候选/Confirmed Memory 运行时代码；保留不可变历史迁移和旧表回滚边界。
- [x] 默认关闭 feature flag，补充 disabled/enabled Spring composition 测试。
- [x] 完成相关后端单元/组合测试、前端页面测试、lint 和 diff whitespace 检查。
- [x] 在一次性 MySQL 8.4 中执行前置 schema 与 `20260814` 正式迁移，验证重复执行、旧数据
  回填、Evidence 幂等、两 Turn 激活、用户禁用保护及 owner/scope 隔离。
- [x] 建立 `auto-memory-v1` 冻结校准 cohort，并让正例通过正式应用安全策略。
- [x] 将提取 Prompt 升级为 `AUTO_MEMORY_EXTRACTION_V2`：JSON 封装 User Turn、收紧无
  Chartbook scope 和推断型 semantic key。
- [x] 将 Agent `300030` 接到独立 DeepSeek 配置链，默认模型为 `deepseek-v4-pro`，不影响
  其他绘图 Agent，也不复制本地凭证。
- [x] 使用官方 `/models` 验证本地 DeepSeek 连接，并完成两个真实 smoke case：CHARTBOOK
  preference 正确提取，Prompt Injection 正确返回空数组。
- [x] 使用 `deepseek-v4-pro` 对 cohort 重复运行三轮，保留首轮失败证据并保存通过门禁的
  `AUTO_MEMORY_EXTRACTION_V3` 校准报告。
- [x] 完成最终运行时代码审查与干净全量验证：后端 `mvn clean test` 共执行 1,959 项测试
  （0 failure、0 error、14 项按 live/integration 开关跳过）；前端 312/312 项测试通过，
  ESLint 0 error，生产构建与 diff whitespace 检查通过。
- [x] 在本地持久化 MySQL 上重复执行正式迁移，并以隔离测试账号完成浏览器端到端验证：
  成功 Turn 创建 work，DeepSeek V4 Pro 完成推断，Item/Evidence 落库。
- [x] 本地端到端测试发现 Context 查询的嵌套 `JSON_ARRAYAGG` 少一个右括号；修复 SQL，并增加
  聚合表达式括号平衡回归测试。
- [x] 验证显式 USER Memory 直接进入 ACTIVE；后续 Turn 的 Context read set 将 Memory 标记为
  `PINNED`，模型能够准确召回跨项目绘图规则。
- [x] 解决推断型 Memory 的语义键漂移：在同一次提取调用中提供受限现有候选，模型复用候选
  的完整规范字段，服务端再次按 scope + key 固定 canonical 内容。

### 2026-08-01

- [x] 增加保守前置筛选；只跳过高确定性的非记忆 Turn，稳定信号优先，模糊输入继续调用模型。
- [x] 增加 USER/CHARTBOOK 分作用域候选读取与 V4 Prompt 归并协议，不增加第二次模型调用。
- [x] 保留 `DISABLED` 候选参与归并，避免同义规则以新 key 绕过用户 opt-out。
- [x] 补充策略、Worker、协议、Spring composition 和 MySQL 候选查询回归测试。
- [x] 在本地 UI → Turn → DeepSeek V4 Pro → MySQL 链路验证同义 key 复用、两证据激活及问候
  跳过 DeepSeek。
- [x] 保留 V4 三轮校准失败证据：安全门槛通过，但空候选归并说明使正例降到 77.78%。
- [x] 升级为 V5：仅在候选非空时注入归并合同；同一 cohort 三轮达到 94.44% / 100% / 0%，
  协议失败为 0，保存完整通过报告。
- [x] 完成 V1.1 候选归并专项 cohort：8 个风险案例各跑三轮，复用、新建与排除全部正确，
  错误归并、跨 scope、DISABLED 绕过、不安全候选接受和协议失败均为 0。
- [x] V1.1 完整后端 `mvn test` 共执行 1,967 项测试，0 failure、0 error；15 项按现有
  live/integration 开关跳过，其中两个 Auto Memory live 门槛已分别通过显式联网运行。
- [x] 完成 V1.2 保守老化：只清理过期、非显式 `OBSERVED`，不按时间修改 ACTIVE、显式或
  DISABLED Memory；任务独立 opt-in，并限制 retention 和单批规模。
- [x] 增加 `20260815` 老化索引与顺序发布包；在一次性 MySQL 8.4 执行正式迁移，并通过 4 个
  集成场景验证批量边界、状态保护、Evidence 级联、重复运行夹具和 owner/scope 隔离。
- [x] V1.2 完整后端 `mvn test` 共执行 1,977 项测试，0 failure、0 error；16 项按既有
  live/integration 开关跳过，正式 MySQL 老化集成测试已另行显式运行通过。
- [x] 完成 V1.3 冲突演进：`semanticKey` 收敛为决策维度；同值继续支持，同维度换值先累计
  CONFLICTING Evidence，两个不同 Turn 才能替换非显式 ACTIVE，显式与 DISABLED 保持受保护。
- [x] 沿用现有 Item/Evidence、行锁事务和归并评估器，没有增加 schema、状态、模型调用、向量库
  或平行框架；一次性 MySQL 8.4 的 5 个集成场景全部通过。
- [x] V6 基础提取与 V1.1 归并回归通过；V1.3 冲突专项 8 例各三轮，challenger、复用、新建与
  排除全部正确，错误归并、跨 scope、DISABLED 绕过、不安全候选接受和协议失败均为 0。
- [x] V1.3 完整后端 `mvn test` 共执行 1,983 项测试，0 failure、0 error；18 项按既有
  live/integration 开关跳过，三个 Auto Memory live 门槛和 MySQL 集成已另行显式运行通过。

### 2026-08-02

- [x] 完成 V1.4-A 候选读取边界：Worker 不再内嵌 USER/CHARTBOOK SQL 组装，默认实现保持原有
  有界顺序与作用域隔离，后续语义适配器可通过同一端口替换。
- [x] 冻结 `auto-memory-retrieval-v1` 的 8 个语义、隔离和生命周期案例，并增加质量门槛评估器；
  当前没有创建向量投影、调用 embedding 或改变 DeepSeek Prompt。
- [x] V1.4-A 完整后端 `mvn test` 共执行 1,989 项测试，0 failure、0 error；18 项按既有
  live/integration 开关跳过，本阶段没有需要联网运行的新测试。
- [x] 完成 V1.4-B Memory 专用投影：CURRENT/CONFLICTING 分开建模，增加 durable outbox、lease、
  desired revision fence、可见性确认、退避重试、旧向量清理和删除 tombstone。
- [x] Pinecone 只保存 HMAC owner/scope 分区和 lifecycle metadata；内容仅进入 embedding 请求，
  Material 与 Memory 继续使用各自的 metadata 合同和 namespace。
- [x] 增加默认关闭的 projection/shadow 组合；shadow 成功或失败均保持 SQL 候选不变，并只记录
  无内容指标。新增 `20260816` 顺序迁移和数据库发布包。
- [x] 在本地 MySQL 8.4 执行正式 `20260816` SQL，5 个集成场景验证 legacy 回填、Evidence
  幂等、revision 推进、旧 lease 拒绝/重排、状态保护、owner/scope 隔离及测试数据清理。
- [x] V1.4-B 完整后端 `mvn test` 共执行 2,010 项测试，0 failure、0 error；18 项按既有
  live/integration 开关跳过，本地 MySQL 集成已另行显式运行通过，未调用真实 Pinecone。
- [x] 完成 V1.4-C 向量 ID → MySQL 权威回查：严格解析自有 ID，批量重验 owner/scope/lifecycle，
  unresolved challenger 折叠回父 Item，并保持向量排序、Memory 去重和每 scope 上限。
- [x] shadow 新增有效回查数与 SQL 重合数指标；Pinecone、回查或指标链路失败都不替换或删除
  SQL 候选，也不记录 Memory 文本和向量 ID。
- [x] 定向 16 项测试和本地 MySQL 8.4 的 5 个集成场景通过；集成场景覆盖 `DISABLED` 回查、
  owner 隔离、未解决 challenger 命中、晋升后旧 challenger 失效和当前值命中。
- [x] V1.4-C 完整后端 `mvn test` 共执行 2,012 项测试，0 failure、0 error；18 项按既有
  live/integration 开关跳过。本阶段没有用模拟向量代替真实召回质量结论，也未连接生产环境。
- [x] 修正 V1 retrieval cohort 与生产 scope 的偏差并升级为 V2：Chartbook Turn 同时允许 USER
  与当前 CHARTBOOK，其他 Chartbook/owner 继续作为硬隔离干扰项。
- [x] 增加显式 opt-in 的真实 Pinecone release gate：直接复用生产适配器、注入 stale terminal
  vector、等待全部 eligible vector 可检索、保存逐 case 排名并确认清理。
- [x] 在隔离 `auto-memory-eval-test-v2` namespace 运行 `multilingual-e5-large`：8/8 目标排第 1，
  相关/禁用召回均 100%，MRR 1.0，未授权、终态和未知返回均为 0；没有连接生产 namespace。
- [x] V1.4-D 完整后端 `mvn test` 共执行 2,013 项测试，0 failure、0 error；19 项按既有
  live/integration 开关跳过，新增 live gate 已另行显式运行并保存通过报告。
- [x] 增加 seed 可复现的本地随机 shadow：真实 MySQL SQL 窗口、生产 shadow 装饰器、真实
  Pinecone query embedding 和 MySQL 回查共同运行；32/32 个旧目标被向量找回，SQL 返回保持
  100%，跨 owner/scope 命中为 0，结束后确认 MySQL 和 Pinecone fixture 已清理。
- [x] V1.4-E 完整后端 `mvn clean test`/增量复核共执行 2,014 项测试，0 failure、0 error；20 项
  按 live/integration 开关跳过。随机 shadow 已另行显式运行并保存报告。
- [x] 完成 V1.5 本地 canary：向量命中沿用 MySQL 权威回查，最多占 16 个候选位，优先合并后由
  SQL 去重补满至 32；搜索或回查失败时原样退回 SQL，不增加 DeepSeek 调用。
- [x] 增加默认关闭的 `AUTO_MEMORY_VECTOR_CANARY_ENABLED`；未开启 projection 时仍为 SQL，
  shadow/canary 同时开启时由 canary 明确优先。本阶段没有修改生产配置。
- [x] V1.5 定向 20 项行为和 Spring composition 测试通过，覆盖优先级、去重、总量上限、两类
  向量故障回退、SQL 故障可见性以及开关组合；完整后端 `mvn test` 共执行 2,022 项测试，
  0 failure、0 error，20 项按既有 live/integration 开关跳过。
- [x] 在 schema-only 隔离 MySQL、独立 Pinecone namespace 和合成账号上完成 V1.5 浏览器端真实
  Turn 验收：SQL 最近窗口刻意排除目标时，向量候选仍被 DeepSeek 精确复用，未生成重复 Memory。
- [x] 使用不可连接的本地 Pinecone endpoint 验证运行时降级：extraction 正常完成、同一 Item
  Evidence 累加，向量投影独立报错；随后删除全部合成向量和隔离数据库，并恢复本地安全开关。
- [x] 跳过 V1.6，完成 V1.7 主 Turn Memory Context 选择：确定性 SQL baseline、可选 ACTIVE/CURRENT
  语义召回、MySQL 权威回查、Chartbook 同键覆盖、总条目/字符预算和向量失败回退。
- [x] Context read-set 升级为向后兼容的 schema v2，持久化选中 Memory ID/version；重试只回查
  已 pin 的集合，不重新执行向量查询，也不因未选中 Memory 变化而漂移。
- [x] V1.7 定向选择、预算、Prompt 顺序、Pinecone filter、read-set codec 和 Spring 组合测试通过；
  本地 MySQL 6 个集成场景全部通过，完整后端 `mvn test` 共执行 2,031 项，0 failure、0 error，
  21 项按既有 live/integration 开关跳过。
- [x] 在首次联网运行前冻结独立 `auto-memory-context-v1` 合成 holdout、预注册门槛并提交；评估器
  不调用 DeepSeek、不写 MySQL，只在隔离 `eval` namespace 临时写入 48 个向量并确认全部删除。
- [x] V1.7 首次盲测未通过门槛：12 个目标中语义选择 Recall@1/3/12 分别为
  8.33% / 16.67% / 66.67%，MRR 0.1933，无关注入率 93.33%；SQL Recall@12 为 0，语义方向有
  提升但不足以上线。同键 Chartbook 覆盖和 owner/scope 隔离均通过，未修改 holdout、阈值或实现
  来美化结果，完整证据保存在 `evaluation/auto-memory-context-v1/results/`。
- [x] 在实现 V1.7.1 前冻结互不重叠的 development 与 V2 holdout；Memory 向量端口保留瞬时
  score，adapter 显式按 score 排序，semantic 成功时执行 cutoff 稀疏选择且不再用 SQL 补位，
  只有向量技术失败才退回 SQL。新增行为、score 解析和未排序 provider 响应回归测试。
- [x] development 复核从 Recall@1 12.5%、无关率 90.67%、平均 9.375 条改善为 100%、46.67%、
  1.875 条；冻结 V2 首次运行达到 Recall@1/3/12 75%、MRR 0.75、无关率 40%、平均 1.25 条，
  但未通过 Recall@12 87.5% 门槛。未按 V2 下调 `0.827`，全部 eval vector 已确认删除；完整后端
  2,036 项测试 0 failure、0 error，22 项按既有 live/integration 开关跳过。
- [x] 在实现 V1.7.2 前冻结 8 正例 + 8 负例的 development-v2 与未见 V3；评估器新增负例误注入
  指标。development-v2 仅用 `0.82` 分数下限和 `0.02` Top-1 分差达到 Recall@3 87.5%、MRR
  0.8125、负例误注入率 0，未为唯一漏召回 Case 增加特殊分支；V3 门槛已在运行前预注册。
- [x] V3 唯一一次运行达到 Recall@1/3/12 87.5%、MRR 0.875，8/8 负例返回空且安全门槛通过；
  但高分候选组多带入 3 条相关主题干扰项，无关率 30% 超过 25% 上限，release gate 失败。未调参
  或重跑 V3，隔离 namespace 的 43 个向量已在 `finally` 中删除并确认不存在。
- [x] V1.7.2 完整后端 `mvn test` 共执行 2,038 项测试，0 failure、0 error；22 项按既有
  live/integration 开关跳过。V3 的质量门槛失败作为独立评估结果保留，不伪装成代码回归或通过。
- [x] 在实现 V1.7.3 前冻结 development-v3 与未见 V4；两者各包含 45 条合成 Memory、6 个
  单目标、2 个双目标和 8 个负例。development-v3 对照证明 Top-1-only 会损失多目标召回，最终
  使用 `0.03` 相对窗口和大于 4 条时整组拒绝，将无关率从 58.8% 降到 12.5%，召回保持 70%。
- [x] V4 唯一一次运行实现 Precision@3 100%、无关率 0、8/8 负例返回空且安全门槛通过；但
  Recall@3/12 仅 60%、正例 Case 命中率 75%，release gate 失败。未修改或重跑 V4，45 个隔离
  向量已删除并确认不存在；完整证据保存在 `evaluation/auto-memory-context-v4/results/`。
- [x] V1.7.3 完整后端 `mvn test` 共执行 2,040 项测试，0 failure、0 error；22 项按既有
  live/integration 开关跳过。V4 质量失败独立保留，semantic feature flag 继续默认关闭。
- [x] 在 V1.8 实现前冻结多意图 planning development 与未见 holdout；两组都包含中英文
  2～3 意图和名词并列/条件句单意图 abstention，SHA 与门槛预注册于
  `evaluation/auto-memory-context-v5/`。
- [x] 增加共享 Recall Planner、独立 tool-free DeepSeek Agent、最多 3 个子查询、逐意图现有门槛、
  合并后单次 MySQL 权威回查，以及 Planner/部分向量故障的有界回退；未修改 V1.7.3 阈值。
- [x] V1.8 development 首次运行 10/10 通过：Case accuracy、多意图覆盖和单意图 abstention
  均为 100%，协议失败为 0；冻结 holdout 尚未运行。
- [x] V1.8 实现提交前完整后端 `mvn test` 共执行 2,053 项测试，0 failure、0 error；23 项按
  既有 live/integration 开关跳过，development live gate 已另行显式运行通过。
- [x] V1.8 唯一一次 V1 holdout 未通过：Case accuracy 80%、多意图覆盖 66.67%、单意图
  abstention 100%、协议失败 0；失败报告保留且 V1 不重跑。
- [x] 在 V1.8.1 修改前冻结 development-v2 与未见 V2 holdout，覆盖不同目标/属性与共享属性、
  对比连接词和时态 `while` 的边界，避免只修补 V1 的两个原句。
- [x] V1.8.1 只扩充宽松模型调用信号，并用 semanticKey 通用判据澄清独立意图；development-v2
  首次 10/10 通过，三项质量指标均为 100%、协议失败为 0，随后固定实现再运行 V2 holdout。
- [x] V1.8.1 唯一一次 V2 holdout 达到 Case accuracy 90%、多意图覆盖 100%、单意图 abstention
  75%、协议失败 0；因一条共享颜色决策被过拆而未通过，保留失败证据且不继续调参重跑。
- [x] 在 V1.8.2 实现前冻结新的 development/holdout 与 SHA，增加候选 Recall@4，避免把候选召回
  和最终 Prompt 选择混成一个指标。
- [x] 完成 facet 候选池、单次 MySQL 权威回查、每 facet 单 winner、不同决策近似并列拒绝和强分隔符
  planner fallback；未增加模型调用、持久化字段或第二个排序器。
- [x] V1.8.2 development-v2 通过冻结门槛：candidate Recall@4 100%、最终 Recall 80%、Prompt
  Precision 88.89%、无关注入 11.11%，权限/生命周期泄漏 0，Prompt parity 100%。
- [x] 锁定实现后唯一一次 V1.8.2 holdout-v2 通过：candidate Recall@4 100%、最终 Recall 90%、
  Prompt Precision 90%、无关注入 10%，权限/生命周期泄漏 0，Prompt parity 100%；未调参或重跑。
- [x] V1.8.2 最终后端 `mvn clean test` 共执行 2,065 项测试，0 failure、0 error；24 项按既有
  live/integration 开关跳过。holdout 已另行显式运行，本地 `amctx_*` Memory/Chartbook 清理计数
  均为 0，隔离 Pinecone vector 也在评估退出前删除并确认不可见。
- [x] V1.8.3 修复 Pinecone 的“fetch 可见但 ANN 尚不可检索”窗口：投影 Worker 只有在 exact
  fetch 与带同一 owner/scope/lifecycle filter 的 query 都能看见目标后才完成，否则保留 work 并
  按原有退避重试；未增加新表、状态或第二套投影协议。
- [x] Planner 升级为 V4 双语 facet，并保留独立属性边界；冻结 development 与 holdout 的
  Planner accuracy、多意图覆盖、单意图 abstention 均为 100%，协议失败为 0。
- [x] V1.8.4 增加按 `DecisionKey` 的 facet winner 分配，防止同一高分 Memory 占据多个独立意图；
  普通 facet 继续使用 `0.80`，只有显式引用既有记忆的原始请求或 facet 使用 `0.76`，没有放宽
  一次性操作和普通绘图请求。
- [x] 固定实现后的 V2 holdout 达到 candidate recall、target recall、完整正例、Prompt precision
  和 Prompt parity 100%，无关注入、负例误注入、forbidden、未授权与 disabled 选择均为 0。
  最后一项“原始显式引用向拆分 facet 传播”修复不再使用 holdout 调参，以精确回归测试和真实 UI
  盲测验收。
- [x] 本地真实 UI → Planner → Pinecone → MySQL → Prompt → Draw.io 盲测完成：最终 Turn 为
  `COMPLETED`，Context read-set 同时 pin 颜色与短标签两条 ACTIVE Memory；持久化画布包含支付库、
  告警库和通知服务，使用珊瑚色系告警节点及短主标签。视觉审查仅提示无法从单张图独立证明
  “是否符合历史偏好”，没有发现结构或连线故障。
- [x] 盲测结束后删除两个隔离 Pinecone vector，清空测试 owner 的 Memory、Turn、Conversation、
  Diagram 与 Agent trace，并把 demo counter 恢复到测试前的 3；复核计数均为 0。
- [x] 最终后端 `mvn clean test` 共执行 2,071 项测试，0 failure、0 error、24 项按既有 live/
  integration 开关跳过；前端相关组件测试 2/2、ESLint 0 error（4 个既有 warning）和生产构建已
  通过。本阶段未向生产数据库或生产 Pinecone namespace 写入数据。
- [x] PR 合并前同步最新 `main`：Auto Memory 页面保留新管理模型，并适配静态导出的
  `/chartbooks/memory?chartbookId=...` 路由与 `Suspense`；旧 Confirmed Memory Proposal Writer
  及其临时关闭开关测试继续删除。冲突解决后 Memory 页面测试、ESLint、29 页静态生产构建和
  2,071 项后端回归再次通过。

当前实现边界：截至 `20260816` 的迁移已在本地 MySQL 8.4 验证，但尚未在目标环境数据库
执行；V6 Prompt 的离线协议、三组 DeepSeek V4 Pro 三轮真实校准、完整本地
Turn → Extract → Persist → Recall 链路及 V1.3 MySQL 冲突演进均已验证。feature flag 仍保持
默认关闭，本地 `.env` 单独开启；V1.2 老化开关也保持默认关闭，且不会时间降级 ACTIVE。
V1.4-E 的投影、权威回查、真实 Pinecone cohort、随机 shadow 和报告均已完成；V1.5 canary
代码和隔离环境端到端验收均已完成，向量召回与 SQL 故障回退通过，但三个相关开关仍保持默认及
本地关闭，普通 Worker 继续只使用 MySQL。V1.7.1 已验证 score 显式排序和稀疏选择能显著降低
无关注入，但固定 `0.827` 在冻结 V2 漏掉两个 raw Top-1 正例，未通过 release gate；因此
`AUTO_MEMORY_CONTEXT_SEMANTIC_ENABLED` 必须继续默认及本地关闭，V1/V2 holdout 都不再用于调参。
V1.7.2 已在新 development-v2 上选定分数下限加相对分差的有界准入规则，但唯一一次 V3 因
候选级无关率 30% 未通过；其 0% 负例误注入支持保留查询级拒绝方向，V3 不再用于调参。
V1.7.3 的 V4 将候选无关率降至 0，但 60% 召回未通过 release gate；V4 同样不再用于调参。
V1.8 已分离多意图查询规划，并保持 semantic 与 multi-intent 两个开关默认关闭；首次 holdout
因漏拆未通过；V1.8.1 的 V2 holdout 将多意图覆盖提升到 100%，但一次共享颜色决策过拆使
abstention 门槛失败。V1.8.2 将 planned facet 改为前四候选、单次 MySQL 权威回查和每 facet 单
winner，并通过新的冻结 holdout；但一次性局部操作仍可能误注入，且近似并列时选择保守放弃。
V1.8.3/1.8.4 已补齐 ANN readiness、双语 facet、跨 facet 决策维度分配和显式引用语义传递；
冻结 holdout 与本地真实 UI 端到端均通过，但 semantic 与 multi-intent 开关仍继续默认关闭，应先
随本地真实使用积累非合成样本，分别观察相关召回、近似并列 abstention 和局部操作误注入，不因
早期受控测试通过而接入生产。目标环境迁移
和 shadow/canary 仍需按第 7 节另行准备，未经用户授权
不执行生产变更。V1.6 完整质量观测按用户决定暂时跳过；V1.7 已完成 SQL 默认选择与可选 semantic
Context 代码，semantic 开关仍默认及本地关闭，未使用合成结果替代真实注入质量证据。严格显式
句子的跨值维度映射和 ACTIVE 使用反馈应基于真实分布单独设计；V4 Flash 的成本/延迟对照也不
阻塞 Pro 上线。
