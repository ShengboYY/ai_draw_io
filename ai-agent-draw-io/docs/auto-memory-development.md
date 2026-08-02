# Auto Memory 方案与开发日志

## 1. 结论

记忆收敛为三层：

1. **Session Memory**：当前会话最近消息与滚动摘要，短期、自动生成，不作为长期事实库。
2. **Chartbook Auto Memory**：当前 Chartbook 内有效的稳定偏好、反馈、项目约定与参考处理规则。
3. **User Auto Memory**：同一用户跨 Chartbook 有效的稳定偏好与反馈。

Canvas State、Chartbook Profile、RAG 继续作为独立上下文源，不并入 Memory。运行时不再存在
Confirmed Memory 或候选确认流程。MySQL 是长期记忆的权威存储；V1.4-A 仅为语义候选检索建立
可替换边界和质量门槛，运行时尚未启用向量数据库。

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

Context 只读取 ACTIVE Memory，并分别构造最多 8 条 Chartbook 与 8 条 User 记忆。查询使用
`JSON_ARRAYAGG`，再在应用侧规范排序，避免 `GROUP_CONCAT` 长度截断和无序聚合导致 pin
摘要不稳定。候选读取与 materialization 会复核版本和内容摘要；并发更新时重试，不把新版本
悄悄代入旧 pin。

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

开关关闭时不创建新工作、不调用提取模型、不暴露新管理 Controller，Context 回到旧
`chartbook_memory` 只读投影。完整产品回滚还需同步回滚前端，因为旧候选 API 不再由新后端
提供。

## 8. 向量数据库的边界

Memory Item 是结构化、带明确作用域和生命周期的数据。MySQL 始终负责权威 Item/Evidence、幂等
合并、用户禁用和乐观锁；向量库只允许成为可删除、可重建的候选检索投影，不能决定激活、冲突
晋升或删除，也不能绕过 owner/scope 过滤。

V1.4-A 运行时仍使用现有 MySQL 有界查询。它先把候选读取从 Worker 中抽成可替换端口，并冻结
语义检索评测合同；没有创建向量 schema、写入投影或调用 embedding。现有 Material RAG 的
Pinecone transport 可以在后续复用，但 Material 专用 metadata、generation 和 projection 不能
直接当作 Memory 索引模型。

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
  `ai-agent-draw-io-infrastructure/src/test/resources/evals/auto-memory-retrieval-v1/cohort.json`，覆盖
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
   未授权候选率 `0%`、终态候选率 `0%`。隔离和生命周期是硬门禁，不能拿相关度作权衡。

本阶段没有定义未使用的通用向量抽象，也没有把 Material RAG 投影强行复用到 Memory。下一步
V1.4-B 再实现 Memory 专用的可重建投影：CURRENT Item 与未解决 CONFLICTING Evidence 分别
建模，写入前携带严格 owner/scope/lifecycle metadata，并提供 durable retry、版本栅栏和 SQL
降级。只有真实检索运行达到上述门槛，才接入 Worker；MySQL 合并事务仍保持唯一裁决者。

## 13. 开发日志

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

当前实现边界：迁移已在一次性 MySQL 8.4 和本地持久化 MySQL 验证，但尚未在目标环境数据库
执行；V6 Prompt 的离线协议、三组 DeepSeek V4 Pro 三轮真实校准、完整本地
Turn → Extract → Persist → Recall 链路及 V1.3 MySQL 冲突演进均已验证。feature flag 仍保持
默认关闭，本地 `.env` 单独开启；V1.2 老化开关也保持默认关闭，且不会时间降级 ACTIVE。
V1.4-A 仅完成可替换检索边界与离线合同，运行时仍使用 MySQL；下一步是第 12 节所述的 Memory
专用投影与影子评估，不接入生产。目标环境迁移和 shadow/canary 仍需按第 7 节另行准备，未经
用户授权不执行生产变更。严格显式句子的跨值维度映射和 ACTIVE 使用反馈应基于真实分布单独设计；
V4 Flash 的成本/延迟对照也不阻塞 Pro 上线。
