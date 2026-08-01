# Auto Memory 方案与开发日志

## 1. 结论

记忆收敛为三层：

1. **Session Memory**：当前会话最近消息与滚动摘要，短期、自动生成，不作为长期事实库。
2. **Chartbook Auto Memory**：当前 Chartbook 内有效的稳定偏好、反馈、项目约定与参考处理规则。
3. **User Auto Memory**：同一用户跨 Chartbook 有效的稳定偏好与反馈。

Canvas State、Chartbook Profile、RAG 继续作为独立上下文源，不并入 Memory。运行时不再存在
Confirmed Memory 或候选确认流程。MySQL 是长期记忆的权威存储；当前不引入向量数据库。

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
| `semantic_key` | 同一作用域内的稳定去重键 |
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
同一 Memory、Turn 和观察摘要只计一次，使重试保持幂等。冲突观察保留为 `CONFLICTING`，
不会直接覆盖已有 ACTIVE 内容；后续明确表达可以 supersede 旧证据。

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
同作用域时复用候选的完整规范字段，新 Turn 只增加 Evidence；相关但不同义或不同作用域时才创建
新键。`DISABLED` 也参与候选匹配，防止用户禁用的规则被换一个 key 自动恢复。

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

迁移创建统一三张表，并把旧 `chartbook_memory` 中 ACTIVE/DISABLED 内容迁入
CHARTBOOK/PROJECT Memory。旧表暂不删除，因为 MySQL DDL 不完全事务化，保留它们可用于数据
核对和开关回滚；旧候选/Confirmed Memory 运行时代码和前端流程已经删除。

发布顺序：

1. 保持 `AUTO_MEMORY_ENABLED=false` 部署兼容代码。
2. 运行 `20260814` 迁移并核对 schema history、表约束、回填数量和失败工作项。
3. 配置并验证 tool-free extractor agent `300030`。
4. 开启 `AUTO_MEMORY_ENABLED=true`，先观察 work backlog、重试率、拒绝率和激活质量。
5. 稳定后再安排独立的破坏性迁移删除旧表；不要与本次切换合并。

开关关闭时不创建新工作、不调用提取模型、不暴露新管理 Controller，Context 回到旧
`chartbook_memory` 只读投影。完整产品回滚还需同步回滚前端，因为旧候选 API 不再由新后端
提供。

## 8. 为什么当前不使用向量数据库

Memory Item 是小规模、结构化、带明确作用域和语义键的数据。初版先按 owner、scope、status、
类型和更新时间召回，MySQL 更容易保证租户隔离、幂等合并、用户禁用和乐观锁。

只有当真实数据证明单用户 Memory 数量显著增长，且结构化召回质量不足时，才增加 embedding
索引。向量库届时只作为可重建的检索投影，MySQL 仍是权威数据源。

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

### 9.1 Agent `300030` 校准门槛

`auto-memory-v1` 当前包含 15 个纯合成案例，Prompt contract 已升级为
`AUTO_MEMORY_EXTRACTION_V5`，覆盖中英文、USER/CHARTBOOK、无 Chartbook、
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

## 10. 开发日志

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

当前实现边界：迁移已在一次性 MySQL 8.4 和本地持久化 MySQL 验证，但尚未在目标环境数据库
执行；V5 Prompt 的离线协议、安全门槛和 DeepSeek V4 Pro 三轮真实校准已经通过，完整本地
Turn → Extract → Persist → Recall 链路及候选非空的同义归并也已验证。feature flag 仍保持默认
关闭，本地 `.env` 单独开启；下一步按第 7 节准备目标环境迁移和 shadow/canary 方案，未经用户
授权不执行生产变更。V4 Flash 的同 cohort 成本/延迟对照不阻塞 Pro 上线，但应在扩大调用量前
完成。
