# RAG 评估计划 V2

## 0. 项目与评估对象

本项目是一款 AI 辅助的内容理解与创作产品。用户可以让它读取 PDF、图片和已有资料，
对内容进行总结、比较、归纳或重新组织，并生成带有来源说明的回答或可视化结果。

资料可以属于用户本人，也可以属于一个允许多份内容共享资料的空间。用户既可以明确
指定资料，也可以直接提问，由系统先检索当前可用资料；没有合适内容时，再根据产品
规则使用模型知识或联网结果。已经引用旧版本的内容继续固定旧版本，新请求默认使用
当前版本。无论 Pinecone、OCR 或视觉能力是否可用，访问权限和版本约束都不能被绕过。

因此，本计划评估的不是单一向量搜索，而是：

> 解析 → OCR/视觉理解 → Evidence → Chunk → 检索 → 重排 → 授权 →
> 上下文选择 → 生成 → 引用

目标是明确区分“资料根本没有被正确表示”“检索没有找到”“找到后被裁剪”“生成阶段
没有正确使用”等不同失败，而不是只给整条链路一个模糊总分。

## 1. 数据集

### 1.1 核心比较集

| 分区 | 数量 | 用途 |
|---|---:|---|
| Development | 250 | 调试、分析、选择候选方案 |
| Validation | 100 | 从候选中选择晋级方案 |
| Legacy holdout | 100 | 仓库内冻结的历史评估分区；不再声称对开发者不可见 |

340 个完成独立复核的案例可以开始正式比较，完成结论必须冻结全部 450 个核心案例。
旧的 38/240 案例结果只保留为历史基线，不用于当前 v-next 的晋级结论。

真正的一次性 final holdout 不把题目或答案提交到开发仓库，由独立保管人按
`fixtures/final-holdout-contract-v1.json` 在基线、候选、评测器和运行 manifest 全部冻结后释放。
仓库里的 legacy holdout 可做回归或历史比较，但不能支撑“完全未见数据”的最终结论。

必须先按 document family、version family 和 fact family 分组，再划分数据集，最后生成
问题。相同资料、不同版本及同一事实的改写不能跨分区。

每个案例只有一个 primary category；语言、模态、难度和是否多跳使用 tags 表示。

| Primary category | 数量 |
|---|---:|
| 精确值、名称和标识符 | 50 |
| 跨语言和语义改写 | 50 |
| 多证据比较与归纳 | 50 |
| 图片和视觉关系 | 50 |
| OCR 与受损文档 | 50 |
| 版本、范围和权限 | 50 |
| 是否检索及检索范围选择 | 50 |
| 无答案和误导资料 | 50 |
| 故障与降级 | 50 |
| **合计** | **450** |

语言目标为中文 193、英文 161、跨语言 96；该比例来自冻结语料的实际文档构成，三类
语言均保留足够样本，不再为了形式对称改变问题语义。

### 1.2 专项守门套件

核心集中的小切片不能单独证明安全性或鲁棒性，因此额外维护：

| Suite | 最少案例 | 目的 |
|---|---:|---|
| Authorization | 60 | 跨用户、跨空间、已移除资料不可泄漏 |
| Versioning | 40 | V1 固定、V2 默认、冲突和缺失版本 |
| Abstention | 40 | 没有答案、证据不足、误导资料 |
| Visual/OCR | 60 | 文本、表格、流程、方向和纯视觉信息 |
| Failure/Recovery | 20 | Pinecone、OCR、视觉和对象存储故障 |

专项套件不用于调参数，只用于通过/失败判断；不得因为开发集表现不好而反复修改案例。

## 2. 评估单位与标注

### 2.1 四个单位

- Document：一份逻辑资料。
- Version：资料的不可变版本。
- Evidence：可核对的事实来源，是稳定的评估真值。
- Chunk：某次索引构建产生的检索单元，可以随实现改变，不作为永久真值。

Evidence 必须定位到页码加文本范围、表格行列、图片区域或结构关系。仅命中页码不算
证据命中。Chunk 必须反向引用它承载的 Evidence。

### 2.2 Evidence group

每个问题包含一个或多个 required evidence groups。每组可以定义：

- ANY：召回任一完整证据即可满足，通常要求 grade 3。
- ALL_PARTS：必须召回多个互补证据才能满足，允许组合 grade 2。

相关性等级：

- 3：单独足以支持一个完整事实。
- 2：只支持事实的一部分，必须与其他证据组合。
- 1：主题相关但不能支持答案。
- 0：无关、已废止、越权或会误导。

Validation 和 Holdout 由两人独立标注；不一致时仲裁。标注 Evidence，而不是标注某一版
Chunk ID，确保更换切分策略后仍可复用同一测试集。

## 3. 指标

### 3.1 解析与表示

- Evidence mapping rate：Evidence 能否映射到最终索引表示。
- Fragmentation rate：完整事实是否被拆成无法恢复的片段。
- OCR CER/WER，以及数字、否定词、时间、专名等关键实体保留率。
- 图像元素、连线方向、表格行列和图文关系识别率。
- 每页 Evidence/Chunk 数、token p50/p95、重复率和小片段比例。

小 Chunk 比例只用于诊断，不能单独判失败。解析阶段必须先达到 Evidence mapping
rate 100%，否则后续召回分数会同时报告“条件召回”和“端到端召回”。

### 3.2 检索

分别报告：

- Document Recall@K：正确资料是否出现。
- Evidence Group Recall@K：满足了多少 required evidence groups。
- Complete Evidence Rate@K：问题所需的全部组是否都已满足。
- Precision@K：返回结果中有多少真正支持问题。
- MRR@10 和 nDCG@10。

建议门槛：

- Evidence Group Recall@10 >= 0.90。
- Evidence Group Recall@40 >= 0.95。
- Complete Evidence Rate@10 >= 0.85。
- MRR@10 >= 0.75，nDCG@10 >= 0.80。
- 精确值和标识符 Recall@5 >= 0.95。

同时给出 macro 和 micro 结果。Macro 以问题为单位，避免多证据问题支配总分；Micro
用于观察所有 Evidence 的总体覆盖。

### 3.3 上下文漏损

在 raw top 40、融合后 top 30、授权后、hydration top 16 和最终 top 8 分别计算
Evidence Group Recall 和 Complete Evidence Rate，并记录：

- Duplicate rate@8。
- Context Precision@8。
- Context token waste。
- 单一来源过度占用率。

每一步相对前一步的 Evidence Group Recall 下降不得超过 0.02，最终 Recall@8
不得低于 0.90。

### 3.4 生成和引用

先把答案拆成 atomic claims，再计算：

- Claim correctness：事实是否正确。
- Faithfulness：事实是否能被实际提供的上下文支持。
- Answer completeness：required claims 是否全部回答。
- Citation precision：引用是否真正支持对应 claim。
- Citation completeness：需要引用的 claim 是否都有引用。
- Citation location accuracy：页码、区域和版本是否正确。

任务预先指定的 required anchor/source version/page 全部出现，只记作
`requiredCitationContractRate`。它是机器可执行的最低输出契约，不等于 claim-level Citation
completeness 或 precision；后两者只有在逐条 atomic claim 独立复核后才报告。没有 claim review 时，
结果必须明确写 `claimMetricsStatus=not_evaluated`，不得用契约分数代替完整引用质量。

建议门限：

- Claim correctness >= 0.90。
- Faithfulness >= 0.95。
- Answer completeness >= 0.90。
- Citation precision >= 0.95。
- Citation completeness >= 0.90。
- 错误用户、空间或版本引用必须为 0。

### 3.5 拒答

单独计算：

- Abstention recall：应该拒答的案例中实际拒答的比例。
- Abstention precision：实际拒答案例中确实应当拒答的比例。
- Unsupported answer rate：证据不足仍然回答的比例。
- False no-answer rate：存在充分证据却声称找不到的比例。

### 3.6 性能和成本

报告端到端及各阶段 p50/p95、embedding 数量、向量查询次数、上下文 token、模型 token
和单次成功答案成本。

候选方案若质量提升不足 0.02，却使 p95 或单位成本增加超过 30%，默认不晋级；涉及安全
修复时可以例外，但必须记录原因。

## 4. 通过规则

### 第一层：硬性不变量

- Authorization/Versioning suite 全部通过。
- 禁止资料进入最终上下文和回答的次数为 0。
- 引用不存在的来源或版本次数为 0。
- 故障时不能伪装成检索成功。

### 第二层：质量

满足核心门槛，且任一主要切片不能显著退化。Validation 用于选方案，Holdout 只能比较
一次冻结基线和冻结方案。

### 第三层：性能

质量和安全通过后，再依据延迟、成本和实现复杂度选择方案，不能用一个综合分数抵消
安全失败。

## 5. 统计与重复性

- 比例指标提供 Wilson 95% 区间。
- 同一问题上的方案差异使用 paired bootstrap，至少 10,000 次。
- Validation 同时比较多个方案时使用 Holm 校正。
- Holdout 不用于继续调参。
- 确定性比较固定输入、提示词、模型和随机参数，只运行一次。
- 稳定性测试从固定子集中抽样，重复 3–5 次，报告均值、最差值和方差。
- LLM 评分器必须与人工样本校准，并保留评分理由；安全不变量不允许只由 LLM 判定。

## 6. 实验顺序

每个实验只改变一个变量：

1. E0：冻结数据、代码、模型和指标，重跑完整基线。
2. E1：解析/OCR/视觉表示，先保证 Evidence 可被正确表示。
3. E2：Chunk 大小、重叠和父子结构。
4. E3：向量、关键词、混合召回以及查询改写。
5. E4：去重和来源多样性。
6. E5：在固定召回上比较 reranker。
7. E6：top 40/30/16/8 与上下文预算。
8. E7：生成、引用和拒答。
9. E8：端到端视觉/OCR 组合。
10. E9：版本、授权、故障和恢复。

E1 内也必须拆分执行，例如先只改变文本块合并，再只改变 OCR 选择，不能同时修改。

## 7. 当前下一步

Pre-E0 已完成：450 个核心案例、独立复核、三分区和语料 provenance 均已冻结。E0/E1
Validation 配对复核与守门 fixture-contract 也已完成；E2 parent-context-500 的 Development
Recall@10 与 flat 持平,MRR@10 低 0.062,未晋级。当前顺序：

1. E4/E5 已完成且候选均未晋级；`ranked-raw-v1` 保持冻结。E6a 已在 26 个冻结的多资料
   Development case 上验证 source-aware selector：26/26 上下文发生变化，gold-evidence recall
   0.5096→0.5673，平均来源数 2.46→3.96。r4 已完成真实 visual/OCR hydration 和 GPT-5.5 paired
   Development，但两臂均因将 cell ID 写作 citation 而 0/6 completion。r5 的完整 citation-output contract
   repair 已完成 formal GPT-5.5 Development 重跑：模型不再返回 cell ID，却因 required canonical anchor 不在各 task
   的 model-visible top-8 evidence 中而仍为 1/6 citation、0/6 completion。下一变量只能是预注册的
   citation-identity/hydration repair。r6 code review 已拒绝从 evaluator gold 推断 canonical anchor；冻结 r4 trace
   因而在 5/5 grounded task 缺 model-visible required evidence，readiness gate 阻止 prompt/model run。通过 gate 的未来
   bundle 必须绑定 hydration export 的路径/SHA-256，runner 在请求前复核其 readiness 与 task/arm/evidence 一致性。下一变量只能是
   预注册的 ingestion source-identity persistence 与 retrieval/hydration evidence-availability intervention。r7 已将
   publisher-owned `source-evidence-identities-v1.json` 接入真实 hydration producer：只允许 exact source text 或 exact
   VISUAL page 身份写入 trace，并持久化 manifest hash；未匹配证据保留 fallback。r7 的真实 Development trace 已证明
   identity 能在 top-40 解析，但 5/5 task 虽通过 contrast，仍有多条 canonical evidence 位于 top-8 外，故 r6 readiness
   gate 阻止 prompt/model run。r8 已预注册为独立的 page-parent evidence availability 干预：在每个 source page
   新增受 900-token 上限约束、保留 Evidence identity 的 citable parent projection，并验证 agent-selected
   material version 已挂载（不据此过滤 chartbook source 或改变排名）；不读取 evaluator 数据。r8 的真实
   Development trace 已清理 80 个临时向量，raw source-aware contrast 为 4/5（80%），但 `dgt-dev-02` 的 architecture
   page-3 visual/OCR artifact 落在 top-8 外（text rank 22、VISUAL rank 24），paired exporter 因而拒绝生成 context。
   该变量不晋级、不能冻结 bundle。r9 保留 page parent 的引用和 lexical 检索、将其排除于 dense candidate pool；真实
   Development trace 已清理 62 个临时向量，但 `dgt-dev-02` control top-8 仍缺 architecture page-3 visual/OCR artifact
   （VISUAL rank 28、TEXT rank 34），因此 paired exporter 在生成 context 前停止。R9 不晋级，Validation 继续关闭。
   R10 已补齐未来 trace 的 embedding 输入 provenance。R11 已预注册为 source-independent 的 visual text-context
   representation：只在 420-token visual 上限内把同页 OCR TEXT 或空间落在 VISUAL 区域内的 NATIVE TEXT 作为 CONTEXT
   附到 VISUAL retrieval text，保留 visual primary citation 与 caption identity，不读取 evaluator 数据。初版只接受 OCR，
   但真实 architecture PDF 的 OCR 会在 canonicalization 被更强的 native label 吸收，代码审查因此拒绝；修订为
   `visual-same-page-text-context-v2` 后本地真实 PDF/OCR seam 已通过。须完成最终独立复审，再另行授权一个临时 Pinecone
   Development trace；其 trace 必须带 R10 embeddingInputManifest。
2. 后续 E9 必须执行在线授权、版本、故障注入与恢复；当前 fixture-contract 通过不能替代它。
3. Validation 暂不打开。仓库可见的 legacy holdout 不再用于“未见最终结论”；外部 final holdout
   按独立保管协议只释放一次。

E2 的 parent-child 方案在运行前固定如下：flat 控制组嵌入 child chunk 的 `retrievalText`；
parent-child 候选组仍以同一个 child chunk ID 检索和计分，但优先嵌入该 child 已持久化的
`parentContext`。该 context 由当前 child 加同页、同 section 的直接相邻 child 组成；只有在本地
tokenizer 计数不超过 500 时使用，超过 500 或缺失时退回 child `retrievalText`，为模型特殊 token
保留输入余量。两组 chunk 数、gold-to-child 映射、模型、top 40 与 E1 canonical 表示均保持一致。
主决策指标为 Development Recall@10 的配对差值；提升小于 0.02 或任一主要切片显著退化则不晋级。

可行性修订：最初预注册为直接嵌入现有最大 900-token parent context；首次候选运行在任何向量 upsert
和质量计分前被 Pinecone HTTP 400 拒绝，观测到实际最大输入为 512 tokens，而客户端固定
`truncate=NONE`。因此只做上述 500-token 上限修正，并从同一新 commit 重跑控制组和候选组；失败运行
不进入质量比较。

E3 的首个候选在运行前固定如下：控制组为 `dense-v1`；候选组为
`hybrid-projection-rrf-v1`。两组都使用 E1 canonical、flat child、固定 gold child、同一
`multilingual-e5-large` dense top 40。候选组另从现有 `LexicalProjection` 取 top 40：拉丁字母/数字
使用确定性 TF-IDF、汉字使用 bigram、任一 exact term 命中加 2；随后按线上同一组参数
`lexical=1.2`、`dense=1.0`、`k=60` 做 weighted RRF 并截断 top 40。控制组也输出 shadow lexical lane，
比较器必须验证两次运行的 dense lane、lexical lane、gold mapping 均逐 case 相同。此离线词法排序覆盖
产品的 projection 信号和 exact boost，但不宣称逐分数复刻 MySQL `NATURAL LANGUAGE MODE`；若候选晋级，
仍须做线上 MySQL 复核。查询改写不在本候选中改变。主指标为 Development Recall@10 配对差值；只有
提升至少 0.02、Recall@40 不降低，且没有样本量至少 20 的主要切片显著退化时才进入 Validation。

配对执行修订：首次按两个独立 Pinecone run 执行时，比较器在计算置信区间前发现同一 dense lane 的
近分候选有名次互换，因此这两个 run 不进入质量结论。修订后的 runner 在同一次 passage/query embedding
和 Pinecone query 上同时计算 dense 与 hybrid 两份结果，确保两组共享逐 case 完全相同的 dense 与
lexical lane；检索算法、RRF 参数、数据和晋级标准均不改变。

E3 的第二个候选在运行前固定为 query-only `evidence-focused-v1`。控制组直接嵌入原始问题；候选组不读取
gold、expected answer、case category 或文档语言，只检测 query 是否含汉字并添加对应语言的直接证据指令：
中文要求查找“包含可直接回答该请求的事实、规则、数值或步骤的原文”，英文要求查找包含同类直接答案
证据的 source passage。两组共享同一次 passage upsert，但分别生成 query embedding 和 dense top 40；
E1 canonical、flat chunk、固定 gold child、模型、tokenizer、source filter 和候选上限均不变，不启用
lexical/RRF、query decomposition 或 target labels。主指标仍为 Development Recall@10 配对差值；提升至少
0.02、Recall@40 不降低且没有样本量至少 20 的主要切片显著退化，才进入 Validation。

E4 的第一个候选在运行前固定为 `evidence-dedup-v1`。当前 722 个 case 的 allowed source 和 155 个
Development dense case 的实际候选均只有一个 source version，因此本候选只回答“单来源内部去重”，不对
来源多样性作结论；后者必须先补多资料挂载 case。两组共享 original query、同一次 passage/query embedding、
同一次 Pinecone dense top-80 与固定 gold child。控制组取前 40；候选组把 retrieval text SHA 相同或
source-backed Evidence ID 集合 Jaccard 至少 0.8 的候选视为重复族，优先保留可引用 chunk，并用更深的
非重复候选回填到 40。E1 canonical、flat chunk、模型、tokenizer 与 source filter 不变，不启用 lexical、
rewrite 或 reranker。只有当 top-40 被替换的重复位置至少占 2%，Recall@10/40 的下降均不超过 0.02、MRR
无显著下降且没有样本量至少 20 的主要切片显著退化时，才进入 Validation。

E4a 结果：Development 的 46/155 个 case 共去掉 112/4,121=2.72% 的基线位置，五个总体质量指标与
所有切片均持平，因此按规则打开 Validation。Validation 的质量仍完全持平，但只有 4/73 个 case、
4/1,575=0.25% 的位置变化，且都在 top 10 之外；去重的实际作用没有复现，故不晋级并保留
`ranked-raw-v1`。这不完成 E4 的来源多样性目标。下一候选必须先构造多资料、图册挂载范围内的 fixture，
覆盖重复证据、互补证据、版本冲突和未授权干扰项；在运行前冻结来源覆盖率、重复占位率、gold-source
recall 与授权泄漏等指标。Holdout 继续密封。

E4b 在运行前固定为真正的多资料图册实验。新增独立 case profile `e4-chartbook-v1`，复用现有长文档而
不制造短摘要：Development 26 例挂载 architecture、workflow、platform-resilience、datacenter-change
四份资料；Validation 20 例挂载 collaboration-governance、material-governance、observability、OTA
四份资料。每例要求至少两个不同挂载来源的 gold evidence，并声明两个同分区、未挂载的干扰来源；fixture
审计必须保证 gold 全在 mounted、mounted/unmounted 不相交且没有跨 split。控制组为同一次 original-query
dense top-80 的原始前 40；候选 `source-diversity-v1` 先执行 E4a evidence dedup，再对 top 10 施加每来源
最多 4 条的软上限，其他来源不足时按原始 rank 回填，最终最多 40。两臂 raw 必须保存完全相同的 top-80，
比较器逐 case 验证。

Development 只有同时满足以下条件才打开 Validation：未挂载泄漏位置为 0；top-40 改变至少 2%；证据
Recall@10/40 下降均不超过 0.02；MRR 无显著下降；gold-source Recall@10 不下降；并且 mean mounted-source
coverage@10 至少提高 0.10，或 mean max-source-share@10 至少下降 0.10。Validation 必须保持泄漏为 0、
证据与 gold-source recall 不退化，并至少复现 0.05 的 coverage 提升或 concentration 降低，候选才晋级。
本实验只改变候选后处理；E1 canonical、flat chunk、original query、dense 模型与挂载过滤全部冻结。

E4b 结果：Development 26 例中，候选重排 21/26=80.8% 的前 40，未挂载泄漏为 0；mounted-source
coverage@10 从 0.683 提升到 0.856，max-source-share@10 从 0.619 降到 0.400，gold-source Recall@10 从
0.423 提升到 0.615。但 evidence Recall@10 从 0.231 降到 0.192（-0.038，超过允许 -0.02），Recall@40
持平、MRR 无显著下降。因此不打开 Validation、不晋级 `source-diversity-v1`，并保持 Holdout 密封。E4
至此完成；下一个单变量实验是 E5 reranking。完整结果与运行锁见
`results/2026-07-22-e4b-chartbook-source-diversity-vs-ranked-raw.md`。

E5 在运行前固定为 OpenAI-compatible `llm-listwise-v1` reranker。控制组保留同一次 original-query
dense top-40；候选只接收用户问题及这 40 个已授权 chunk 的 source version、每次调用临时映射的短 opaque
候选 ID（`c01` 至 `c40`）与最多 800 字符 retrieval text，并以 temperature 0、`thinking=disabled` 返回 JSON `rankedIds`。短 ID
只在本次调用中映射回真实向量 ID，避免长向量 ID 挤占模型输出长度；它不可读取 gold anchor、expected answer、
case category、split 或未挂载资料；未知、重复、遗漏或非 JSON ID 均丢弃，再按 dense 原顺序回填，因此候选
只能重排同一 top-40、不能制造或移除证据。Development 使用冻结 core 的 155 个 dense-eligible case；模型、
endpoint fingerprint、prompt fingerprint、top-40 和 tokenizer 均写入两臂 raw，且比较器逐 case 验证
top-80 完全相同。每次调用记录 JSON 接受数、总延迟、prompt/completion token；这些只作成本与可用性报告，
不作为质量晋级替代。

Development 只有在有效 JSON 输出至少覆盖 95% case、Recall@10 至少提高 0.02、Recall@40 不下降、MRR 无
显著下降，且没有样本量至少 20 的主要 Recall@10 切片显著退化时才打开 Validation。由于候选固定重排同一
40 个候选，Recall@40 必须精确持平；否则输出或比较器无效。Validation 需要复现正向 Recall@10 方向且无
质量回退，候选才晋级。此实验不改变 dense 召回、chunk、query、授权过滤或后处理，Holdout 继续密封。

E5 结果：使用 `deepseek-v4-pro` 的 Development 完整运行覆盖 155 例，两个臂的 top-80、533 chunks 和
所有固定控制项逐 case 一致。候选只有 5/155（3.23%）次返回有效 JSON，低于 95% 可用性门槛；其余响应安全
回填 dense 顺序。Recall@10 为 0.8968→0.8968（+0.0000），Recall@40 持平，MRR@10 为
0.7160→0.7209（+0.0048，95% 配对区间 [0.0000, 0.0145]），所有 n≥20 的主要 Recall@10 切片持平。
因此不打开 Validation、不晋级 `llm-listwise-v1`，并继续密封 Holdout。完整结果、raw 与运行时 corpus-lock
snapshot 见 `results/2026-07-22-e5-deepseek-v4-pro-reranking.md`。

为使后续晋级反映 draw.io agent 的真实价值，新增 `fixtures/drawio-core-v1.json` 作为主指标集：Development
93 例（architecture blueprint、workflow handbook、planning workshop scan），Validation 22 例（collaboration
governance），Holdout 27 例（agent recovery runbook）。资料家族不跨 split；泛领域 case 保留为鲁棒性指标，
不再单独决定 draw.io 功能晋级。下一批 fixture 将补充资料驱动的制图、结构编辑、图/扫描件转可编辑 XML 与
citation-bound 输出；在此之前，先对既有 `drawio-core-v1` 重跑 dense/E1 基线。

生成层补充：`fixtures/drawio-generation-tasks-v1.json` 作为历史 12-task fixture 保留；其中 repository-visible
holdout 不再充当 final unseen set。active `drawio-generation-tasks-v2.json` 只含 Development/Validation，且
结构编辑、版本安全、布局、权限与 citation-bound edit 都提供模型可见的 input XML。评测器另行检查稳定 cell ID、
受保护 label/attribute、指定修改、禁止 edge 和两列 geometry；这些 evaluator-only assertions 不进入 prompt。

E6 修订预注册分两步。E6a 只判断 selector 是否形成有效实验臂：复用冻结的 26 个 E4b 多资料 Development
retrieval pool，控制组为 raw top-8，候选先按完全相同 chunk ID 去重、再做 source-aware top-8。当前 raw
并未携带所有 candidate 的 Evidence ID，因此 E6a 不声称执行 Evidence-level dedup。至少 20% case 的 chunk 顺序或
集合必须变化，否则实验直接判无效，不进入质量解释。E6a 已达到 26/26，并报告 anchor-level gold-evidence recall
和来源覆盖；旧 47-case 单资料导出因 47/47 两臂相同而退役为 negative diagnostic。

E6b/E7 才做生成比较：先为同一批 v2 task 导出一一对应的 hydrated control/candidate context，冻结 task、context、
prompt bundle、model/request 参数和完整 run manifest。检索、授权过滤、gold、模型和输出评测器保持一致，只改变
context selection。Development 比较 XML parse、edit assertions、required citation contract 与经独立 claim review
得到的 answer completeness/citation completeness/precision/correctness/faithfulness；claim review 必须由
两名不同的具名 reviewer 逐 task 覆盖 v2 fixture 冻结的全部 required claim ID，并记录仲裁方法；评测器拒绝
缺 task、重复或挑选 claim。当前样本少于 20 时只报 exact paired case 结果，不作
n>=20 slice 推断；满足预注册门槛后才打开 Validation。

E6/E7 接线约束：实际传给生成模型的每条 context 必须保留 chunk 的正文或可验证视觉/OCR artifact、anchor ID、
source version 和页码/区域。任务的 XML 断言、required anchor 和 expected answer 只能由评测器读取，不能进入
模型提示。此前 text/table-only 的 E0/E1 runner 明确排除了 `visual_flow` 与 OCR anchor，因而不能单独作为
含流程图或扫描件任务的多模态生成上下文；这些任务必须先通过对应的 visual/OCR hydration 路径，才能与控制组
做有效比较。

## 2026-07-23 R12 修订：评测合同修复与 v3 Draw.io 核心任务

本节是当前有效的增量修订；上文 v1/v2 与 R4-R11 描述保留用于历史复现，不再作为新正式运行的输入合同。

- active fixture 升级为 `fixtures/drawio-generation-tasks-v3.json`，包含 20 个 Development 和 20 个
  Validation 任务。覆盖资料生成图、结构编辑、扫描/OCR 转可编辑 XML、流程/时序重建、版本固定、资料范围、
  权限、降级状态与阈值图；Development 使用 7 个资料家族，Validation 使用 5 个互不重叠家族。
- 每个任务显式声明 `sourceScopeMode`：`selected_only` 只能检索该次点名的资料版本，
  `chartbook_auto` 只能在当前分区冻结的图册挂载资料内自动选择，`none` 禁止资料检索。producer、exporter
  与 prompt builder 使用同一规则；不再把“显式选择”仅当作校验后仍搜索全图册。
- source evidence identity 由资料发布侧的 exact-text 或 visual-page manifest 提供。模型只看到
  `CIT-###`、source version 与 page；canonical anchor ID 留在私有 resolution map，响应返回后才解析，
  防止 evaluator identity 泄漏。
- paired readiness 改为 candidate-required/control-measured：candidate 必须完整具备所有必要证据和视觉/OCR
  artifact，否则禁止模型调用；control 缺失保留为基线实验结果，不再导致整组实验无法开始。两臂仍需达到
  20% context change、来源范围和 provenance gate。
- corpus-lock 只有在两个不同注册人类 reviewer 分别提交覆盖全部 450 core case 的
  `material-rag-human-review-v1` 文件，且 ledger 校验文件路径、SHA-256、reviewer ID、完整 case coverage
  与逐 case verdict 后才能为 `frozen`。AI reviewer 加一名人类、仅写 reviewer 名称或旧 v1 ledger 均不满足。

新的执行顺序固定如下：

1. 生成 v3 fixture 与 publisher source identity manifest，运行离线结构审计；此时锁只能是 `candidate`。
2. 两名人类独立审阅全部 core case；修复任何 `needs_fix` 后重新生成、重新审阅并形成 v2 ledger。
3. 重新运行 E0 readiness，确认 `READY` 且 corpus-lock 为 `frozen`，提交干净 commit。
4. 只在 Pinecone test/dev 临时 namespace 运行 v3 Development hydration；保存 embedding/provenance manifest，
   在 `finally` 删除本次向量并验证清理。
5. exporter 先验证 source scope、artifact、candidate readiness 与 paired contrast；全部通过才构建 control/
   candidate prompt bundle。
6. 使用同一 GPT 模型与冻结参数运行 20+20 Development paired generation，先做确定性 XML/edit/citation
   评测，再由两名不同 reviewer 覆盖全部 frozen claim ID。Development 未过门槛不得打开 Validation。
7. 只有 Development 晋级才执行一次 Validation hydration/generation；不得据此调参。通过后执行 E8
   multimodal end-to-end 与 E9 safety/recovery。
8. baseline、candidate、evaluators、manifest 与代码全部冻结后，才由独立保管人生成仓库外 final holdout，
   最多运行一次。

历史勘误：旧 ledger 将 AI+human 误计为“双人审阅”，因此此前依赖该锁的正式/Validation 结论降级为
provisional diagnostic；dated result 与 dated lock 不改写。R11 的原始运行锁可从 commit `22152510`
恢复，其 SHA-256 为 `872642afc468ce94e741ef7374c6f9752885ef91b6d1e941600d3acde0cc6db4`。R8 的
architecture page-3 text/VISUAL rank 为 22/24，R9 为
34/28；它们来自不同 chunk/channel，不能简写成同一个排名的前后变化。

## 2026-07-23 R13 修订：自动全量审计 + 项目负责人抽样确认

项目负责人在当前任务中查看并批准了 7 个已展示的代表样例。为避免不必要的 450×2 人工负担，同时不伪造
“双人独立审阅”，active review governance 改为 `automated-full-owner-spot-check-v1`：

- 自动合同审计逐一覆盖全部 450 core case；任何 query、answerability、gold anchor、evidence group、
  split/source、category context 或 generation contract 错误都会阻止冻结。
- owner spot-check policy 固定 `controlled-130`（流程视觉）、`controlled-624`（无答案），以及
  `dgt-dev-03`（结构编辑）、`dgt-dev-10`（扫描转 XML）、`dgt-dev-17`（SEV 图例）、
  `dgt-val-04`（OTA 流程）和 `dgt-val-17`（权限矩阵）。
- owner approval 与 policy 分别保存、哈希并由 ledger v3 绑定；auditor 独立校验必需 ID、文件路径、
  SHA-256、reviewer kind 和 approve decision。
- corpus lock 写入 `reviewGovernance=owner_spot_checked` 与
  `independentHumanReview=not_claimed`。这足以用于本项目的正式内部比较，但发布结果时不得描述成
  independent double-human reviewed。

本修订取代 R12 的 active 双人完整审阅阻塞项；R12 仍保留为更严格的可选替代路径。下一步为从新的干净
frozen commit 运行 v3 Development hydration，仍不得提前读取或调参 Validation。

## 2026-07-23 R14 预注册：source-independent visual coverage

R13 的首个正式 Development trace 来自 commit `5c24c10a`。真实 PDF/OCR/Pinecone 路径完成 19 个检索任务、
186 个 chunks 并在 `finally` 删除临时向量，但输入 gate 在模型调用前发现两个选择缺口：

- `dgt-dev-10` 的正确 planning scan page 5 artifact 已在 top-40，但位于 rank 19；原
  `source-aware-top8-v1` 没有为必须看图的任务保留多个不同视觉页。
- 修复该选择缺口后的诊断探针继续在 `dgt-dev-12` 失败；datacenter page 3 的 canonical visual identity
  未出现在 top-40，因为 7 页资料按 15% page fraction 只选了 2 个视觉页。

这是 Development 调参，未读取 Validation，且两次 gate 都发生在 prompt/model 前。R14 只改变一个变量族：
视觉 artifact 的 source-independent availability。

1. controlled chartbook 的 visual hydration 仍受 12-page 和每页 3-region 上限约束，但在这些短文档中覆盖所有
   本地检测出的视觉候选页；选择过程不读取 task、required anchor、XML assertion 或 expected answer。
2. 对 fixture 明确声明为 multimodal 的任务，candidate top-8 从冻结 top-40 中最多保留 4 个不同图像
   artifact：先取各有图资料源的最高排名 artifact，再按全局排名补满；图像去重只使用 publisher
   source/path/SHA，不使用 task 目标源或 gold。
3. control 保持 raw top-8，缺 artifact 作为 baseline 失败测量；candidate 仍必须含匹配资料的 artifact，并通过
   required canonical evidence readiness。
4. 代码、计划、测试和 corpus lock 提交后，必须从新 commit 重新跑 Development hydration；旧 R13 trace 只作
   诊断，不得直接作为正式模型输入。

晋级条件保持不变：20 个任务精确覆盖、retrieval provenance/source scope/artifact hash 全部有效、19 个检索任务
top-40 完整、candidate required evidence 全部可见、paired context change rate 至少 20%。任一失败都不调用模型；
Validation 继续关闭。

## 2026-07-23 R15 预注册：scoped candidate-pool completeness

R14 的 clean-commit rerun 成功生成 trace 并清理向量，但 exporter 在 prompt/model 前发现：
`dgt-dev-04` 与 `dgt-dev-12` 异常返回 0 条，而 `selected_only` 的 `dgt-dev-20` 稳定返回 28 条。后者不是
缺失——该唯一允许资料本身只有 28 个 projected chunks；旧的“所有任务必须 40”无法区分合法小范围与远端空结果。

R15 不改变检索、排序、视觉覆盖或任务内容，只补齐 producer/exporter completeness contract：

- trace 的 retrieval manifest 记录每个 mounted source 的 projected chunk count；
- 每个检索任务必须精确返回 `min(40, sum(允许资料的 projected chunks))`；
- 因此 `selected_only` 的 28/28 合法，图册自动任务的 0/40 仍 fail-closed；
- 缺少 source count、负数、零 projected chunks、排名不连续或重复 chunk 仍拒绝。

R14 的空结果 trace 保留为诊断，不用于 generation。更新代码、测试、计划和 corpus lock 后，必须从新的 clean
commit 再跑 Development；仍不得调用模型或打开 Validation，直到全部输入 gate 通过。

## 2026-07-23 R16 预注册：empty-query retry

R15 trace 正确记录 source chunk counts：17 个自动图册任务返回 40，`dgt-dev-20` 返回合法 28/28，但
`dgt-dev-03` 随机返回 0/40。结合 R14 中随机落在另两个 task 的 0/40，根因是 Pinecone 对刚建立并已通过
全局 searchability probe 的 namespace，个别带 source filter 的查询仍可能暂时返回空列表；空列表不是异常，
所以旧 transient-exception retry 不会重试。

R16 仅改变 live research runner 的可靠性边界：original 与 rewritten query 若返回空列表，按冻结次数做有限
指数退避；非空立即继续；耗尽仍空则抛错，由 `finally` 清理向量且不写正式 trace。正常非空结果、query、
embedding、filter、top-k、ranking、selector 和所有评测门槛均不改变。R15 trace 只作诊断；提交后仍须从新
clean commit 重跑 Development，模型和 Validation 保持关闭。

## 2026-07-23 R17 预注册：publisher source-page artifact registry

R16 的 bounded empty-query retry 在真实运行中触发并恢复一次 rewritten query；随后全部自动图册任务为
40/40，selected-only 为 28/28。artifact gate 仍在 `dgt-dev-02` 失败：architecture page 3 artifact 位于
raw rank 31，但旧 producer 的 source/page registry 只覆盖 architecture 与 planning scan；进一步检查发现
datacenter、payment 和 field-audit 的冻结原图文件也未注册。视觉选择、OCR 和检索可以运行，但 trace 无法绑定
这些原图的路径与 SHA。

R17 仍属于同一 source-independent artifact-availability 变量族：

- publisher-side registry 按 source version + page 暴露 active Development 的冻结原图：architecture page 3、
  planning scan pages 1–6、datacenter page 3、payment pages 3–4、field-audit scan pages 1–5；
- registry 不读取 task、required anchor、XML assertion、expected answer 或 Validation；
- multimodal candidate 在 8 个槽位中最多保留 4 个 source-diverse distinct artifacts，先取每个有图来源的
  首个 artifact，再按全局排名补满；
- artifact 文件不存在、路径越界、SHA 不匹配、candidate 缺匹配来源 artifact 或 required evidence 不完整
  继续 fail-closed。

R16 trace 只作诊断。更新计划、合同、测试与 corpus lock 并提交后，从新 clean commit 重跑 Development；
仍不得调用模型或打开 Validation。

## 2026-07-23 R18 预注册：indexed-vector completeness denominator

R17 live run 的检索与清理成功，但 scoped-pool gate 报 `dgt-dev-20` 实际 28、期望 35。根因是 R15 manifest
统计了 projection manifest 的全部 chunks，其中 7 个为 lexical-only；Pinecone 只 upsert
`DENSE_AND_LEXICAL`，所以 completeness 分母必须是实际 indexed dense vectors，而不是所有 projections。

R18 只修正 provenance 口径：

- producer 写入 `sourceIndexedVectorCounts`，逐 source 仅统计与 upsert 相同的
  `RetrievalIndexMode.DENSE_AND_LEXICAL`；
- exporter 要求精确 `min(40, sum(允许资料的 indexed vectors))`；
- 新字段优先，旧 `sourceProjectionChunkCounts` 只用于历史诊断 trace 的兼容读取；
- embedding、query、filter、retry、visual registry、selector、context 和 gate 均不改变。

R17 trace 只作诊断。新 commit 的 Development trace 必须使用 indexed-vector 字段并通过全部输入 gate 后，
才允许构建 prompt；Validation 继续关闭。

## 2026-07-23 R18 结果与 R19 下一变量：relevance-preserving selection

R18 clean-commit Development trace 首次通过完整 scoped-pool 口径：18 个 chartbook-auto task 为 40/40，
`dgt-dev-20` 为 28/28；一次 rewritten empty query 经 R16 retry 恢复，运行后向量已删除。exporter 同时通过
provenance、source scope、artifact path/SHA、task coverage 和 paired-effectiveness（18/19 changed，
94.74%）。但最终 candidate-required evidence gate 失败：

- candidate 完整：2/19（`dgt-dev-03`、`dgt-dev-11`）；
- raw top-8 control 完整：4/19（`dgt-dev-02`、`dgt-dev-05`、`dgt-dev-10`、`dgt-dev-11`）；
- 14/19 task 的全部 canonical required evidence 存在于 raw top-40，但现 selector 多数未保留进 top-8；
- 5/19（`dgt-dev-07/08/12/14/15`）在 raw top-40 的 publisher canonical identity 本身不完整。

因此 `source-aware-with-artifact-coverage-top8-v1` 明确不晋级，禁止生成 prompt 或调用模型。R19 必须拆分两个
不混淆的 Development-only 变量：

1. publisher identity availability：修复 source-owned exact/visual identity 与真实 hydrated evidence 的绑定，
   不读取 task required anchors；目标是 raw top-40 canonical availability 19/19。
2. relevance-preserving context selection：从 raw ranking 出发保留 query relevance，再施加 bounded artifact
   coverage；不能使用 task target source、required anchors、XML assertions 或 expected answer。candidate
   complete-task count 必须至少高于 control 的 4/19，且 7 个 multimodal task 全部保留可验证 artifact。

两个变量必须分别预注册、测试和比较；在 candidate readiness 19/19 前不得调用模型，Validation 保持关闭。

### R19a 预注册：verified source-page visual identity binding

先只处理 4/5 个已定位为 publisher hydration 身份绑定缺口的任务，不改变 query、embedding、ranking 或
selector：

- `visual_page` 身份可以由同 source version + page 的真实冻结图片证明，即使 Pinecone 返回的是该页的
  TEXT projection；只有 artifact registry 找到常规文件时才允许绑定，缺文件继续 fail-closed；
- publisher registry 新增已存在的 handbook page 4 原图 `drawio-workflow-plan-route.png`，其余 registry
  保持不变；运行时不读取 task required anchors、XML assertions 或 expected answer；
- `dgt-dev-08` 属于另一类 text candidate recall 缺口，本轮不得用 page-only text identity 或 task gold
  兜底。R19a 目标是 raw top-40 canonical availability 从 14/19 提升到至少 18/19；达到后再单独处理
  text retrieval 与 relevance-preserving selector。

R19a 从新的 clean commit 仅重跑 Development trace；运行后删除临时 Pinecone vectors。若 candidate
readiness 仍非 19/19，继续禁止构建 prompt、调用模型和打开 Validation。

### R19a 结果与 R19b 预注册：publisher-identity lexical reservation

R19a 在 clean commit `a4924c84` 使用冻结生产 tokenizer 重跑，passage/query hashes 与 R18 相同，19 个检索
任务的 scoped pools 全部完整，raw top-40 publisher canonical availability 达到 19/19；临时 Pinecone vectors
已删除。旧 selector 仍不晋级：raw top-8 control 完整 13/19，candidate 完整 12/19。模型、Validation 与
holdout 继续关闭。

R19b 只改变 top-40 → top-8 selector，并冻结为 `publisher-identity-lexical-reservation-top8-v1`：

- 从 raw top-8 开始，最多预留 6 个具有 publisher canonical identity 的不同 identity group；
- relevance 只比较 model-visible request 的 ASCII token 与 publisher `sourceEvidenceId` 的连字符 token；
  token 至少 3 字符，冻结通用指令/图形 stopword；exact match 计分，双方至少 4 字符时允许前缀 match；
- 按 overlap 降序、raw rank 升序决定 reservation，替换未被预留的 raw tail，最终恢复 raw rank 顺序；
- selector 不读取 task `sourceVersion`、required anchors、citation/XML assertions、expected answer、
  ground truth 或 Validation；`retrieved:<chunkId>` 不参与 identity relevance；
- Development 诊断预期 12/19 task 改变（63.16%）、candidate required-evidence 19/19，并通过 7 个 declared
  multimodal task 的 source-matching artifact gate。任一 gate 未通过则不生成 prompt、不调用模型。

R19b 只在已冻结的 R19a trace 上做本地确定性导出，不重新上传 Pinecone vectors。实现、测试、计划、日志和
corpus lock 提交后，才允许生成正式 paired context；Validation 仍关闭。

### R19b 结果与 R20 预注册：Draw.io bilingual retrieval terms

R19b 从 clean commit `b26c0682` 正式重跑，186 chunks、passage hash、scoped pools 均与 R19a 一致；一次
original query 空结果经 bounded retry 恢复，向量已删除。publisher-identity selector 使 15/19 task 改变，
control 完整 3/19、candidate 完整 16/19，但未达到 19/19，故不晋级且未调用模型。

失败必须分层：

- `dgt-dev-16/17` 的 required canonical evidence 位于 raw top-40，但 selector 未全部保留；
- `dgt-dev-13` 的 `dcc-threshold` 本次不在 raw top-40，因此不能继续用 selector 修补；
- raw top-40 publisher canonical availability 为 18/19，证明下一变量必须回到 query retrieval。

R20 只把 `ResearchQueryRewriter` 从 evidence-focused-v1 升级为
`drawio-bilingual-evidence-focused-v2`。原 query、English/Chinese evidence instruction、embedding、
top-k、RRF、identity binding、selector 和所有 gate 保持不变。对于 Latin request，仅按出现的 Draw.io
领域短语追加冻结中英检索词：

- `threshold` / `human review` / `escalation` → `阈值` / `人工复核` / `升级`；
- `vector search` / `object storage` / `canvas save` → `向量检索` / `对象存储` / `画布保存`；
- `incident` 或 `SEV-*` → `事件` / `严重级别`。

扩展不读取 task source、required anchors、assertions、answers、ground truth 或 Validation；无匹配短语的
query 与 v1 完全一致。R20 的 Development gate 要求 raw top-40 canonical availability 19/19、candidate
readiness 19/19、7/7 artifact、scoped pools 完整且 changed-rate ≥20%。提交后从新 clean commit 重跑；
失败则继续禁止 prompt、模型和 Validation。

### R20 wiring audit 与 R21 预注册：evidence-focused hydration lane

R20 从 clean commit `52c6ade9` 正式运行并清理向量，但不能解释为双语 query 效果。代码审计确认
`writeTaskHydrationTrace` 固定读取 `result.metrics(PostprocessMode.RANKED_RAW)`；该 map 与 original-query
lane 共用 `originalRanks`。R20 rewritten query 的 hash 已改变且请求确实执行，但其 candidates 只保存在
`QueryMode.EVIDENCE_FOCUSED`，没有进入 hydration trace。此次 raw 16/19、candidate 15/19 仅作 wiring
失败诊断，不作为 R20 效果结论。

R21 只修复 producer lane：

- task hydration trace 从 `QueryMode.EVIDENCE_FOCUSED` 序列化 candidates，不再从 original
  `PostprocessMode.RANKED_RAW` 取值；
- retrievalRun 新增 `candidateQueryMode` 与 `queryRewriteFingerprint`，exporter 必须验证两者为冻结值；
- original 与 rewritten query、embedding、top-80 provider request、top-40 pool、retry、identity、
  selector、task、artifact 和 gate 全部不变；
- 新单元测试固定 hydration lane，防止 rewritten intervention 再次被静默丢弃。

R21 Development gate 仍为 raw 19/19、candidate 19/19、artifact 7/7、scoped pools 完整与 changed-rate
≥20%。不得挑选多次运行中的最好结果；只接受预注册 clean commit 的一次正式 trace。失败则模型和
Validation 继续关闭。

### R21 结果与 R22 预注册：original + rewritten query RRF

R21 从 clean commit `c08edce3` 正式运行，trace 与 exporter 均确认 evidence-focused lane 和 R20 rewrite
fingerprint。scoped pools 完整，original lane 两次空结果经 bounded retry 恢复，向量已删除。raw canonical
availability 14/19，control 3/19、candidate 13/19，因此 evidence-focused-only 不晋级，模型保持关闭。

R22 不再在 original 与 rewritten lane 中二选一，而只复用同一次运行已经产生的两个 rank list：

- 对 original top-80 与 rewritten top-80 进行等权 reciprocal-rank fusion，`k=60`；
- 同一 vector 的两个 lane 分数相加，按 fused score 降序、最佳单 lane rank 升序、vector ID 升序确定性
  打破并列，取前 40；
- trace 记录 `candidateQueryMode=original-evidence-rrf-v1` 与
  `queryFusionFingerprint=equal-rrf-v1:k60:original1.0:rewritten1.0`，exporter fail-closed 验证；
- 不增加 Pinecone 请求，不改变原/改写 query、embedding、provider top-80、retry、identity、selector、
  artifact、task 或 gate，也不读取 target source、gold、assertions、answers 或 Validation。

R22 只允许预注册 clean commit 的一次正式 Development trace。gate 仍为 raw 19/19、candidate 19/19、
artifact 7/7、scoped pools 完整和 changed-rate ≥20%；失败则禁止 prompt、模型与 Validation。

R22 已在实现 commit `08fe2566` 完成：runner 仅嵌入 original 与 evidence-focused 两条 query lane，
用固定 `equal-rrf-v1:k60:original1.0:rewritten1.0` 对两组 top-80 排名融合，并将 fused top-40 作为
task hydration 唯一候选 lane。trace 与 exporter 同时绑定 query mode、rewrite fingerprint 和 fusion
fingerprint。完整 ingestion-worker 测试为 94/94（其中 6 个 live 测试按设计跳过），analysis 为
122/122，E0 corpus audit 仍为 READY；尚未进行 R22 Pinecone 正式运行。

### R22 结果与 R23a 诊断预注册：保留双 lane 排名谱系

R22 从 clean commit `9b68c322` 完成唯一一次正式 Development trace，一次 original 空结果经冻结 retry
恢复，186 个临时向量已删除。scoped pool 与 7/7 visual/OCR artifact gate 通过，changed-rate 为
14/19；raw canonical availability 为 17/19，candidate 为 16/19，未达到两个 19/19 gate，因此禁止
prompt、模型和 Validation。

失败分层为：`dgt-dev-01`、`dgt-dev-19` 的 required identities 不在 fused top-40；`dgt-dev-17`
的三个 required identities 均在 fused rank 28，但未进入 selector top-8。当前 trace 没有分别保存
original/rewrite top-80 rank，不能区分前两个缺口是单 lane retrieval miss 还是 RRF truncation。

R23a 只允许增加诊断谱系，不改变 R22 candidates：

- trace 为每个 task 记录 original top-80、rewritten top-80 的 ordered chunk IDs，以及 fused top-40
  对应的两条 lane rank；字段只来自 provider 返回结果，不读取 target source、gold、assertions 或 answers；
- RRF、query、embedding、top-k、retry、identity、selector、artifact 和所有 gate 保持不变；
- 只允许一次显式授权的 Development diagnostic trace，结果不能替代 R22，也不能择优；
- 根据 `dgt-dev-01/19` 的 lane ranks 再预注册 R23 正式 intervention；诊断前不得猜测新的 fusion 权重。

R23a 已在实现 commit `ef77bbdc` 完成。trace 为每个 task 写入两条 provider-ordered top-80 chunk ID
序列与 fused top-40 的原/改写 lane ranks；exporter 验证 fingerprint、上限、lane 去重、fused 顺序和
每个 rank。实现不改变原有 candidates。完整 ingestion-worker 测试为 95 tests、0 failures、6 live
skips，analysis 为 124/124，E0 audit 仍为 READY；Standards/Spec 双轴代码审阅均为 0 个实质问题。
尚未进行 R23a Pinecone diagnostic trace。

E6b 的本地导出合同已冻结为 `fixtures/drawio-generation-paired-hydration-contract-v1.json` 与
`analysis/export_drawio_paired_hydration.py`。active Development 图册明确挂载 architecture、workflow handbook
与 planning-workshop scan 三个版本；导出器从同一 retrieval trace 的 raw top-8 和 source-aware top-8 产生一任务
一对 context，并拒绝非 top-40 pool、漏 task、越权来源、空正文、越界/哈希不符的视觉 artifact，以及少于
20% task 改变的伪对照；architecture flow 和 planning scan 两任务的两臂还必须都有已验证 artifact。visual crop
rerun 1/2 证明 page-3 artifact 可被索引但未进入 broad-chartbook raw top-8；因此 r4 预注册为一次独立
representation 干预：先只用 native structure 选出 visual page，再只对这些已选真实页面运行本地 Tesseract，随后
在最终 canonical evidence/chunk 中加入 OCR 文本，同时保留原始图像 artifact 给模型判断箭头与结构。它不读取 task
`requiredAnchors`、XML assertions 或 expected answer，不修改 raw top-8 的排序规则。r4 已在临时 Development
namespace 成功导出 6 个 paired context：`dgt-dev-02` 的 OCR companion chunk 位于 raw rank 4 并带冻结图像，3/5
retrieval-required task 的 context 改变（60%，超过 20% gate）。control/candidate 的 6 条 Development prompt 已各自
冻结。r4 GPT-5.5 formal Development run 随后完成：两臂 XML parse 都为 6/6、required citation contract 都为 1/6、
completion 都为 0/6，故未晋级且 Validation 继续关闭；下一变量只能是预注册的 citation-output contract repair，
不能把该 tie 解释为检索质量结论。

真实 hydration 的 producer 是 ingestion worker 的 opt-in live test
`ControlledPdfDenseRecallLiveTest#shouldExportDrawioDevelopmentTaskHydrationFromTheRealMultimodalPipeline`。
它只读取 v2 Development task 的 model-visible request 和 frozen chartbook source scope，不读取 task 的
`requiredAnchors` 或 evaluator XML assertions；它通过真实 PDFBox → OCR → canonical evidence → chunk →
Pinecone top-40 路径生成 trace。planning scan 必须由 `MATERIAL_RAG_TESSERACT_EXECUTABLE` 指向实际可执行的
Tesseract；缺少 OCR、Pinecone test/dev namespace 或显式 `MATERIAL_RAG_TASK_HYDRATION_JSON` 输出路径时 test
直接 skip，不能降级为 text-only 结果。hydrate trace 只按 publisher-owned source identity manifest 的 exact
source-text 或 exact VISUAL-page 规则写入 canonical citation ID；该路径不读取 evaluator ground truth。其余每个
top-40 chunk 仍以稳定的 `retrieved:<chunkId>` citation ID 完整导出，绝不因未命中身份而丢弃正文。视觉与扫描
artifact 使用 frozen 原始页面图并记录 SHA-256。
