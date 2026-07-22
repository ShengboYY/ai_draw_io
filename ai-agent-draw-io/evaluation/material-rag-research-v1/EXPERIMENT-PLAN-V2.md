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
| Holdout | 100 | 只比较冻结基线和最终方案 |

340 个完成独立复核的案例可以开始正式比较，完成结论必须冻结全部 450 个核心案例。
旧的 38/240 案例结果只保留为历史基线，不用于当前 v-next 的晋级结论。

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

1. E4a 单资料 `evidence-dedup-v1` 已完成：Development 生效率 2.72% 且质量持平，Validation
   生效率仅 0.25%，不晋级。E4 继续补多资料图册挂载 fixture，并预注册来源多样性指标。
2. 后续 E9 必须执行在线授权、版本、故障注入与恢复；当前 fixture-contract 通过不能替代它。
3. Holdout 保持密封，直到基线与最终候选均冻结后只比较一次。

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
