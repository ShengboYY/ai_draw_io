# 材料 RAG 评估 · 实验记录

本文件是评估实验的**主线日志**:记录做过哪些实验、每个实验改了什么变量、配置、结果、
结论和晋级决策。详细指标在各 `results/YYYY-MM-DD-*.md` 里,本文件负责串流程、记决策、
指下一步。**每做完一个实验,往「实验时间线」追加一节。**

计划与目标见 [EXPERIMENT-PLAN-V2.md](../EXPERIMENT-PLAN-V2.md) 与 `experiment-plan-v2.json`。

---

## 方法论(每个实验都遵守)

- **单变量**:一次只改一处,其余全冻结,这样提升能干净归因。
- **干净基线**:跑某实验前,把不属于该实验的在途改动 `git stash` 掉,保证 pipeline 纯净。
- **门槛**(dense 文本检索,来自 plan §3.2):Recall@10 ≥ 0.90、Recall@40 ≥ 0.95、MRR@10 ≥ 0.75。
- **数据集划分**:development 调试/选型,validation 复核晋级,holdout 只在冻结后比较一次。
- **切片**:不只看总分,按语言/类别切片看,防止「整体赢、局部退化」。240 语料上切片 n=8~44 时结论不稳
  (E1 validation 曾出现噪声性「退化」);**升级到 450 后 dev 切片 n=20~110,per-slice 已可信**。
- **真实检索**:live Pinecone(integrated `multilingual-e5-large`, dim 1024),真索引真查询,
  跑完 `finally` 删向量。**starter/serverless 层索引延迟大**,需 `MATERIAL_RAG_INDEX_WAIT_ATTEMPTS=240`
  (默认 12s 会误报 not-searchable)。

## 产品边界(决定评估重心)

- 检索是 **per-user、范围两级**:用户完整库 ⊃ 图册挂载子集;图册内绘图**只能用挂载子集**,
  非图册绘图用完整库。**永远不跨用户。**
- 两条授权不变量:**跨用户隔离** + **图册收窄**(图册内不得漏到用户库里未挂载的资料)。
- 因为「绘图=针对少数资料出图」,候选池天然小 → **检索不是主瓶颈**,重心在解析/表示/结构/
  忠实。RAG 评估范围到「生成+引用(E7)」为止;出图的视觉/布局质量属可视化层,不在此评估。

## 语料与基线状态

- **核心集**:**450**(数据集 v-next),三维精确命中(split 250/100/100、9 类别各 50、语言 zh193/en161/cross96),
  17 项结构检查全 PASS,双人复核(AI 首轮 + 人工确认),**E0 lock FROZEN**。语言比例按数据集实际构成
  (中文场景文档偏多)定,每语言仍远超 per-slice 所需。
- **数据集 240→450 升级**:每类 case 补到 ≥50 以让 per-slice 统计可信(validation 曾证明 n=8~44 时切片结论
  不稳)。新增 5 篇跨领域 digital 文档 + 2 篇扫描件(补 ocr),用 ILP + `query-selection.json` 精确削减到 450。
  E0/E1 已在 450 的 Development 与 Validation 重跑;test 的 projection 列表已加入 5 篇 expansion 文档。
- **守门套件**:**272**。五个计划内最低数全部精确达到:authorization 60、versioning 40、
  abstention 40、visual-ocr 60、failure 20;另有 `guard_chartbook_scope` 12 与 regression 40。
  fixture-contract **272/272 PASS**,但它只执行计数、grounding link 与场景契约,不替代 E9 的在线授权、
  故障注入、检索和生成测试。
- 生成确定性可复现;`query-selection.json`(ILP 削减)与 spec 模块纳入 provenance。**39 篇文档、402 锚点**。
- 关键提交:语料 `14141132`、E0 适配 `c8f4845e`、E1 晋级 `89f8bbfe`、E1 复核 `228eb416`、
  数据集 v-next `74caf0e7`、守门 261 `c1398181`、E0/E1@450-dev `6af49385`、
  冻结与可复现实验 seam `70cb75e7`。

---

## 实验时间线

> 注:下方 E0/E1 最初在 **240 语料(v1)** 上跑,**已在 450 语料(v-next)重跑**(见「E0/E1 重跑 @ 450」节);
> 240 结果保留作历史对比。450 上 E1 提升更大(+0.148 R@10)且无切片退化。

### E0 — 基线(dense, development) · 2026-07-21 · ❌未达门槛(预期)

- **目的**:冻结代码/语料/模型,量出纯 dense 检索的现状,作为后续对比基准。
- **配置**:`canonical-v4`(无段落合并),development split,text/table anchors,724 chunks。
- **结果**:Recall@10 **0.839**、Recall@40 **0.919**、MRR@10 0.663。两门槛均未过。
- **诊断**:5 个完全检索不到(rank 0),3 个是版本/检索决策类抽象规则(query 与 gold 表述语义
  错位);multi_evidence 最弱;精确时间戳 miss。
- **结论**:基线偏低是预期诊断,指明后续改进方向。**不晋级(它就是基准)。**
- 详见 [2026-07-21-e0-development-dense-baseline.md](2026-07-21-e0-development-dense-baseline.md)。

### E1 — 文本块合并(paragraph merge) · 2026-07-21 · ✅晋级

- **假设**:E0 的 miss 主因是 PDFBox 把段落拆成物理碎行、证据被切散(fragmentation)。合并成
  完整段落应提升 recall。
- **变量(唯一)**:`CanonicalPageAssembler` `canonical-v4 → v5`,新增 `mergeSameLineParagraphRuns`
  + `mergeContiguousParagraphLines`。其余全同 E0。
- **development 结果**:Recall@10 0.839→**0.903**、Recall@40 0.919→**0.952**(两门槛均过)、
  MRR@10 0.663→0.744;chunks 724→**399**(证据去碎片化)。E0 的抽象规则 miss 多数被捞回。
- **validation 复核**:Recall@10 **0.895**(差临门一脚)、Recall@40 0.921、MRR@10 0.751。
  - 泛化良好(dev/val R@10 仅差 0.008,**非过拟合**);
  - 但 dense-only 就在门槛边缘,余下差距属检索侧(E3/E5),不再靠合并;
  - dev 的 multi_evidence 退化(0.667)在 val 未复现(0.875),**非系统性**,是单点波动;
  - 切片方向 dev/val 翻转,**实证 per-slice 需 n≥50**。
- **结论**:提升真实且泛化 → **晋级进 baseline**。注意 Recall@40 有一部分来自 chunk 变少、
  候选池变小(需靠 MRR/Recall@1 校正判断真实排序提升)。
- 详见 [2026-07-21-e1-paragraph-merge-vs-e0.md](2026-07-21-e1-paragraph-merge-vs-e0.md)。

### E0/E1 重跑 @ 450 语料(v-next) · 2026-07-22 · ✅ E1 更强晋级,无退化

- **背景**:数据集升级到 450 后,E0(v4)/E1(v5)在新语料重跑;dev 测样本 62→**155**,per-slice 可信。
- **E0@450 新基线**:R@10 0.748、R@40 0.800、MRR 0.601(比 240 的 0.839 低——450 更大更多干扰,是更真实基线,920 chunks)。
- **E1@450**:R@10 **0.897**、R@40 **0.955**(过门槛)、MRR 0.715;chunks 533。**提升 +0.148 R@10,比 240 的 +0.065 更大**。
- **关键**:multi_evidence 0.55→**0.85**(240 上"退化"到 0.67 被证实是小样本噪声);**所有切片都提升、无退化**,E1 干净晋级。R@10/MRR 的剩余差距是检索侧(E3)的活。
- 详见 [2026-07-22-e0-e1-on-450-corpus.md](2026-07-22-e0-e1-on-450-corpus.md)。

### E0/E1 Validation 配对复核 @ 450 · 2026-07-22 · ✅提升泛化,❌ dense-only 未过门槛

- **控制变量**:同一提交 `b78f014c`、同一冻结锁、Validation、`multilingual-e5-large`、top 40;
  只切换 `canonical-v4 → canonical-v5`。100 个 Validation 核心用例中,73 个 answerable
  text/table/multi-evidence 用例进入本次 dense 评估;其余 visual/OCR/no-answer 必须在对应阶段评估。
  Holdout 未打开。运行时锁 SHA-256 为 `40891a8b…`,精确快照保存在
  `2026-07-22-e0-e1-run-corpus-lock.json`;当前工作树的 lock 会因比较器等 provenance 脚本后续修订而变化,
  不用于冒充这两次历史运行的锁。
- **E0**:mapping 53/73=0.726;端到端 R@10 0.699、R@40 0.726、MRR@10 0.547、920 chunks、
  20 个 rank-0 miss;conditional-on-mapping R@10 0.962、R@40 1.000。
- **E1**:mapping **62/73=0.849**;端到端 R@10 **0.822**、R@40 **0.836**、MRR@10 **0.646**、
  533 chunks、12 个 rank-0 miss;conditional-on-mapping R@10 0.968、R@40 0.984。
- **配对提升**:mapping **+0.123**;端到端 R@10 **+0.123**(95% CI [0.041, 0.205])、
  R@40 **+0.110**([0.027, 0.192])、MRR@10 **+0.099**([0.014, 0.189])。
  提升在 Validation 泛化且三个主要差值区间不跨 0;机制主要是多 9 个 case 被正确映射。
- **切片**:English、Chinese、multi-evidence、retrieval-decision 提升;cross-language 与 exact lookup
  持平。table 从 1.000 降到 0.875(n=8,配对区间 [-0.375,0.000]),未显著但列为 E2 观察项;
  failure 只有 n=3,不可据此作稳定结论。
- **决策**:保留 E1 作为已证实的表示层组件,但 E1 本身仍低于 0.90/0.95/0.75 门槛,
  不能宣称 dense-only pipeline 完成。按计划进入 E2,若提升 <0.02 再转 E3。
- 详见 [2026-07-22-e0-e1-validation.md](2026-07-22-e0-e1-validation.md),原始逐用例结果保存在
  `2026-07-22-e0-validation-raw.json` 与 `2026-07-22-e1-validation-raw.json`。

### E2 — flat leaf vs parent-context-500 · 2026-07-22 · ❌不晋级

- **假设**:对 child vector 嵌入现有相邻 parent context,可能降低抽象/多证据 query 与局部 leaf 的
  语义错位,同时继续用 child ID 做 gold/citation 边界。
- **控制变量**:同一提交 `8c0ba9a5`、同一运行锁、Development 155 个 dense-eligible 用例、E1
  `canonical-v5`、`multilingual-e5-large`、top 40、533 个 child ID;唯一变量是嵌入 flat
  `retrievalText` 或 ≤500-token `parentContext`(超限/缺失退回 leaf)。每个 anchor 的固定 gold child
  IDs 写入两组 raw 并由比较器逐 case 强制相同。Holdout 与 Validation 未打开。
- **可行性修订**:最初直接嵌入现有 parent 的方案实际达到 512 tokens,在 `truncate=NONE` 下被
  Pinecone HTTP 400 拒绝且未进入 upsert/计分;因此在看候选质量前改为 500-token 安全上限。
- **结果**:mapping 两组均为 149/155=0.961;flat→parent 的 R@1 **0.574→0.490**、R@5
  0.852→0.832、R@10 **0.877→0.877**、R@40 0.935→0.961、MRR **0.695→0.633**。
  R@10 配对差值 **0.000**(95% CI [-0.045,0.045]),未达到 +0.02 晋级线;R@1 -0.084
  ([-0.161,-0.006])与 MRR -0.062([-0.114,-0.009])均显著退化。
- **切片**:English、exact lookup、version/authorization 名义提升;multi-evidence -0.050、failure
  -0.069。主要 R@10 slice 的 paired CI 均未严格排除 0,但整体早期排序显著变差,不适合 draw.io
  agent 的小候选池场景。
- **运维观察**:首次边界输入 400、两次可见性调用卡住及一次 429 均用精确 run prefix 清理;
  research runner 增加了遵守 `Retry-After` 的有界 transient retry。最终两组各删除 533 个向量。
- **决策**:**不晋级 parent-context-500**,保留 flat leaf,按预注册顺序转 E3 dense + lexical hybrid。
- 详见 [2026-07-22-e2-parent-context-vs-flat.md](2026-07-22-e2-parent-context-vs-flat.md)。

### E3 — dense vs projection-backed hybrid RRF · 2026-07-22 · ❌不晋级

- **假设**:现有 lexical projection 可补足 dense 对多证据、版本/授权规则的语义错位；候选使用
  TF-IDF/CJK bigram/exact +2 的确定性离线 lexical top 40，再按线上参数 `lexical=1.2`、
  `dense=1.0`、`k=60` 融合。该 lexical scorer 不冒充 MySQL FULLTEXT 的逐分数复刻。
- **控制变量**:提交 `df6b9f46`、Development 155 例、E1 canonical、flat leaf、533 chunks、
  `multilingual-e5-large`、top 40、固定 gold child；仅切换 dense 与 hybrid。Validation/Holdout 未打开。
- **配对修订**:首次两个独立 run 被比较器发现 dense 近分名次互换，在统计前判为不可比较；最终从同一次
  passage/query embedding 和 Pinecone query 同时产出两组结果，155/155 dense/lexical lanes 完全一致。
  所有运行均删除各自 533 个向量。
- **结果**:dense→hybrid 的 R@1 0.600→0.613、R@5 0.877→0.871、R@10
  **0.890→0.897**、R@40 0.961→0.961、MRR 0.718→0.723。R@10 差值仅 **+0.006**
  (95% CI [-0.019,0.032]),未达到 +0.02。
- **切片**:multi-evidence +0.100,但 exact lookup -0.057;两个询问“未知 identifier/date”的 case
  从 dense rank 3/2 被 broad lexical overlap 推到 rank 22。English -0.021、Chinese +0.029,区间均跨 0。
- **决策**:**不晋级 `hybrid-projection-rrf-v1`**,保留 dense baseline,不打开 Validation。E3 下一候选
  单独测试 query rewrite；后续 lexical 只能考虑意图门控/重排,不能无条件以 1.2 权重融合。
- 详见 [2026-07-22-e3-projection-hybrid-vs-dense.md](2026-07-22-e3-projection-hybrid-vs-dense.md)。

### E3 — original vs evidence-focused query · 2026-07-22 · ❌不晋级

- **假设**:不猜答案,只把问题改写成寻找直接 source evidence 的指令,可缩小抽象 query 与证据段落的
  表述差距。中文/英文各用同语言固定前缀,不读取 gold、答案、类别或文档语言。
- **控制变量**:提交 `cf22d679`、Development 155 例、E1 canonical、flat leaf、dense-only、533
  chunks、同一次 passage index、`multilingual-e5-large`、top 40、固定 gold child;唯一变量是 query text。
  Validation/Holdout 未打开,运行后删除 533 个向量。
- **结果**:original→evidence-focused 的 R@1 **0.600→0.548**、R@5 **0.877→0.826**、R@10
  **0.890→0.871**、R@40 0.961→0.961、MRR **0.720→0.671**。R@1、R@5、MRR 的退化区间
  均不跨 0;R@10 差值 -0.019(95% CI [-0.052,0.013])。
- **切片**:retrieval-decision +0.036,但 Chinese -0.043、table -0.040、failure -0.069、
  version/authorization -0.056。通用前缀帮助抽象规则,却稀释了数值、故障和多证据 query 的具体词。
- **决策**:**不晋级 `evidence-focused-v1`**,保留 original dense baseline。E3 的 hybrid 与 query rewrite
  均无合格提升,按顺序进入 E4 去重/来源多样性。未来 rewrite 只能作为另行预注册的意图门控方案。
- 详见 [2026-07-22-e3-evidence-focused-query-vs-original.md](2026-07-22-e3-evidence-focused-query-vs-original.md)。

### E4a — ranked raw vs evidence deduplication · 2026-07-22 · ❌不晋级，E4 继续

- **假设**:同一资料内重复或高度重叠的证据块会浪费 top 40；按 retrieval-text SHA 或 source-backed
  Evidence-ID Jaccard≥0.8 合并重复族、优先保留可引用块，可在不损失召回的前提下释放候选位置。
- **控制变量**:提交 `068d7550`、E1 canonical、flat leaf、original query、dense-only、533 chunks、
  固定 gold child；两组共享同一次 Pinecone top-80，唯一变量是取原始前 40 或执行
  `evidence-dedup-v1` 后处理。Holdout 未打开。
- **Development**:46/155 case 发生变化，去掉 112/4,121=**2.72%** 的基线位置，达到预注册 2%
  生效门槛；R@1/5/10/40 与 MRR 的配对差值均为 **0.000**，因此进入 Validation。
- **Validation**:质量指标与所有切片仍全部持平，但只有 4/73 case 发生变化，去掉
  4/1,575=**0.25%**，且都在 top 10 之外；Development 的去重发生率没有复现。
- **边界**:当前所有 case 的 allowed source 与实际候选池都只有一个 source version，本实验只验证
  单资料内部去重，**没有验证跨资料来源多样性**。两次完成运行均删除 533 个临时向量；一次 Validation
  DNS 失败发生在 upsert/计分之前，不计入结果。
- **决策**:**不晋级 `evidence-dedup-v1`**，保留 `ranked-raw-v1`。E4 尚未完成；下一步先构造紧扣
  draw.io 图册挂载场景的多资料 fixture，覆盖重叠、互补、已取代与未授权资料，再预注册并评估
  source diversity。Holdout 继续密封。
- 详见 [2026-07-22-e4-evidence-dedup-vs-ranked-raw.md](2026-07-22-e4-evidence-dedup-vs-ranked-raw.md)。

### E4b — 多资料图册来源多样性 · 2026-07-22 · ❌不晋级，E4 完成

- **假设**:draw.io 图册同时挂载多份资料时，dense top 10 可能被单一资料占据；top-10 每来源最多 4 条的
  soft cap 应提高互补资料覆盖，并且不损失直接证据。
- **控制变量**:提交 `72f817ed`、独立 `e4-chartbook-v1` Development 26 例、每例 4 份挂载长资料与
  至少 2 个 gold 来源、E1 canonical、flat leaf、original query、dense-only、533 chunks、同一 Pinecone
  top-80、相同 mounted-source filter。只切换 `ranked-raw-v1 → source-diversity-v1`；两臂 top-80
  逐 case 验证一致。Validation/Holdout 未打开。
- **来源目标实现**:候选重排 21/26=**80.8%** 的前 40；mounted coverage@10 **+0.173**
  (0.683→0.856)，unique sources@10 2.731→3.423，max-source-share@10 **-0.219**
  (0.619→0.400)，gold-source Recall@10 **+0.192** (0.423→0.615)，两臂均为 0 未挂载泄漏。
- **质量失败**:evidence R@10 **0.231→0.192（-0.038）**，超过预注册最多 -0.02 的损失；R@40 持平，
  MRR -0.0016 且区间跨 0。英文 `e4cb-dev-025` 被从 rank 10 重排至 11，是唯一 top-10 evidence loss。
- **运维**:两次意外重叠启动使用不同 run prefix；最终只归档一套完整的成对输出。两个 exact-prefix cleanup
  查询均返回 0，确认没有残留 synthetic vectors。
- **决策**:**不晋级 `source-diversity-v1`，不运行 Validation**。固定的每来源上限以相关证据的早期排序
  换取了更好的来源覆盖，不适合作为 draw.io agent 的默认候选后处理。E4 至此完成，保留
  `ranked-raw-v1` 并按计划进入 E5 reranking。
- 详见 [2026-07-22-e4b-chartbook-source-diversity-vs-ranked-raw.md](2026-07-22-e4b-chartbook-source-diversity-vs-ranked-raw.md)。

### E5 — DeepSeek V4 Pro listwise reranking · 2026-07-22 · ❌不晋级，E5 完成

- **假设**:在不改变 original-query dense top-40 成员的前提下，以 LLM listwise 重排可将直接证据提前，提升
  Recall@10。
- **控制变量**:提交 `55d1d756`、Development `core-v1` 155 例、E1 canonical、flat leaf、original query、
  dense-only、ranked raw、533 chunks；两臂共享同一次 Pinecone top-80，并逐 case 验证完全相同。候选使用
  `deepseek-v4-pro`、temperature 0、`thinking=disabled`、短 opaque ID 与每候选最多 800 字符，不读取 gold 或
  split metadata。Holdout 未打开。
- **可用性失败**:模型只产生 5/155=**3.23%** 个有效 JSON，远低于预注册的 95%。无效或空输出安全回填 dense
  顺序，因此没有夸大质量结果。
- **质量**:R@10 为 **0.8968→0.8968（+0.0000）**，R@40 持平；MRR@10
  **0.7160→0.7209（+0.0048，95% 配对区间 [0.0000, 0.0145]）**。所有 n≥20 的主要 R@10 切片均持平。
- **运维**:155 次调用共 603,561 prompt token、14,558 completion token，模型累计延迟 357,625 ms。运行后 exact-prefix
  cleanup 返回 0，确认无 synthetic Pinecone vector 残留。
- **决策**:**不晋级 `llm-listwise-v1`，不运行 Validation**。它未达到 JSON 可用性和 +0.02 R@10 的双重硬门槛；
  保留 `none-v1`，Holdout 继续密封，下一步转 E6 或另行预注册不同 reranker 协议。
- 详见 [2026-07-22-e5-deepseek-v4-pro-reranking.md](2026-07-22-e5-deepseek-v4-pro-reranking.md)。

### E5b — GPT-5.5 listwise reranking · 2026-07-22 · ❌不晋级

- **控制变量**:同 E5 的 155 Development case、E1 canonical、flat leaf、original query、dense top-80 与 top-40 成员；GPT-5.5 因 API 仅允许默认 temperature、使用 `max_completion_tokens`，单独记录为 E5b。
- **结果**:有效 JSON 21/155=**13.55%**；R@10 与 R@40 均持平，MRR@10 +0.0129（95% 配对区间 [0.0032, 0.0258]）。
- **决策**:未达到 95% JSON 可用性与 +0.02 R@10 门槛，**不运行 Validation**；exact-prefix cleanup 为 0，Holdout 密封。
- 详见 [2026-07-22-e5b-gpt-5-5-reranking.md](2026-07-22-e5b-gpt-5-5-reranking.md)。

### drawio-core-v1 — E0/E1 Development check · 2026-07-22 · ⚠️方向一致，样本不足

- 新主指标集的 dense-eligible Development 子集为 47 例；E1 R@10 0.8511→0.8723（+0.0213）、R@40 +0.0426、MRR +0.0512。
- 所有配对 95% 区间仍跨 0，不能用这次小样本复测打开 Validation 或替代原 450-case 结论。
- **决策**:保留 E1 工作表示；先补 draw.io 制图/XML/结构编辑案例，扩大 `drawio-core` 后重跑 E0/E1。

---

## 当前状态与下一步

- 已完成:E0 基线 → E1 表示层改进 → 核心集升级并冻结到 450 → Development 与 Validation
  配对重跑 → 守门最低数补齐并执行 fixture-contract。Validation 证明 E1 提升真实,也证明
  dense-only 尚未达门槛。
- **下一步**:E5 已完成且候选不晋级；进入 E6 context selection 的单变量设计。original query、dense、
  flat chunk 和 `ranked-raw-v1` 保持冻结，Holdout 继续密封。

## 开放问题 / 待办

- [x] 核心集扩到 450、每类 50——per-slice 已可信(450 E0/E1 上 multi 的"退化"被证实是噪声)。
- [x] 守门补 **「图册收窄」** 套件——已建 `guard_chartbook_scope`(12 例)。
- [x] 守门套件补齐——总量 272;计划内五套件均达到最低数并完成 272/272 fixture-contract。
- [x] E0/E1 在 450 Validation 配对复核——E1 的提升区间不跨 0,但绝对门槛未通过。
- [x] E2 flat vs parent-context-500——R@10 +0.000、MRR -0.062,不晋级,转 E3。
- [x] E3 projection hybrid——R@10 +0.006、R@40 +0.000,exact lookup 退化,不晋级。
- [x] E3 evidence-focused query——R@10 -0.019、MRR -0.049,不晋级,转 E4。
- [x] E4a 单资料 evidence dedup——质量持平，但生效率 2.72%→0.25% 未在 Validation 复现，不晋级。
- [x] E4b 多资料图册挂载 fixture 与来源多样性评估——来源覆盖改善，但 R@10 -0.038 超过硬门槛，未晋级。
- [x] 英文切片小样本噪声——450 上 en(n=47)R@10 0.894,与其他语言接近,非真问题。
- [ ] E6/E7(上下文选择、生成引用)尚未开跑。

## 如何跑一个实验(运行手册)

1. **保证干净基线**:把不属于本实验的在途改动 `git stash push <files>`;确认 `git status` 干净。
2. **加载环境**(在 `ai-agent-draw-io/`):
   ```bash
   set -a; source .env; set +a
   export MATERIAL_RAG_TOKENIZER_PATH="$PWD/tmp/material-rag-tokenizer/tokenizer.json"
   export MATERIAL_RAG_RESEARCH_SPLIT=development   # 复核用 validation;holdout 只最后一次
   export MATERIAL_RAG_CANONICAL_MODE=e1-v5         # E0 用 e0-v4
   export MATERIAL_RAG_RESULT_JSON="$PWD/evaluation/material-rag-research-v1/results/run-raw.json"
   export MATERIAL_RAG_COMMIT_SHA="$(git rev-parse HEAD)"
   export MATERIAL_RAG_INDEX_WAIT_ATTEMPTS=300 MATERIAL_RAG_DELETE_WAIT_ATTEMPTS=150  # starter 层(450 语料)
   ```
3. **跑**:
   ```bash
   mvn -q -pl ai-agent-draw-io-ingestion-worker -am \
     -Dtest='ControlledPdfDenseRecallLiveTest#shouldMeasureDenseRecallAfterTheRealPdfAndChunkPipeline' \
     -Dsurefire.failIfNoSpecifiedTests=false test
   ```
4. **记录**:把指标写进 `results/YYYY-MM-DD-<实验>.md`,并在本文件「实验时间线」追加一节。
5. **决策**:提升 ≥0.02 且不显著退化任何主要切片 → 晋级(提交变量);否则记录后回滚。
6. 向量在带 `test`/`dev` 前缀的 namespace 中 upsert,同一次运行内删除。
