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
- **数据集划分**:development 调试/选型,validation 复核晋级。仓库可见的 legacy holdout 只作历史评估；
  真正 final holdout 由独立保管人按 contract 在全部冻结后释放一次。
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
  19 项结构检查全 PASS,双人复核(AI 首轮 + 人工确认),**E0 lock FROZEN**。语言比例按数据集实际构成
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

### draw.io generation-task expansion · 2026-07-22 · ✅已冻结

- 新增 12 个端到端任务：Development 6、Validation 3、Holdout 3，按资料家族隔离。
- 覆盖资料驱动制图、结构编辑、扫描/OCR 转可编辑 XML、版本与权限约束、故障恢复及 citation-bound 输出。
- 每例固定 source version、anchor、XML 结构断言和引用断言；用于 E7/E8，不稀释纯 retrieval 主指标。

### E7 — GPT-5.5 draw.io XML/citation contract pilot · 2026-07-22 · ⚠️仅验证评测管线

- **范围**:只发送了 6 个冻结的 Development 合成任务；Validation 和 Holdout 没有发送。
- **严格复核**:可解析 XML 3/6=50.0%；按 anchor、source version 与页码同时匹配计算的 citation assertions
  为 0/6=0.0%，任务完成为 0/6。原 pilot prompt 只要求 anchor ID，因此其输出不能被当作 citation-location
  质量结果。
- **边界**:提示只给任务文字和 anchor ID，尚未提供检索到的证据正文、版本或位置；这只能验证 XML/citation evaluator 和模型响应契约，**不能**评估 RAG faithfulness、E6 上下文选择或 candidate/control 差异。
- **决策**:不晋级、不打开 Validation。下一步把冻结的 control/candidate context bundle（来源文本、版本、citation location）接入相同任务，先在 Development 比较任务完成、citation completeness 与可人工复核的 faithfulness。
- 详见 [2026-07-22-e7-gpt-5-5-development-pilot.md](2026-07-22-e7-gpt-5-5-development-pilot.md)。

### E6/E7 — evidence-grounded prompt wiring · 2026-07-22 · ✅本地接线完成

- 提示构建器只向模型传入 hydrated evidence 的正文/visual-OCR artifact、anchor、source version 和位置；
  XML assertions、expected answer 与评测用 required anchor 不进入 prompt，防止真值泄漏。
- corpus audit 现验证 12 个 generation task 的 anchor、资料版本、split 与 citation assertion；当前 v1 任务全部通过。
- **阻塞边界**:现有 drawio-core E0/E1 live runner 只纳入 text/table anchor，明确排除了 visual-flow 与 OCR；
  因此它不能为架构流程和扫描件制图任务生成真正的多模态 context。没有发送新资料给模型，也没有运行
  control/candidate 或打开 Validation/Holdout。
- 下一实现是导出带 source location 的 multimodal hydration context，再用本 prompt builder 对 Development
  做 E6/E7 成对比较。
- 详见 [2026-07-22-e6-e7-prompt-wiring.md](2026-07-22-e6-e7-prompt-wiring.md)。

### E7 — GPT-5.5 fixed multimodal evidence · 2026-07-22 · ❌不晋级

- 已冻结 6 个 Development prompt bundle：每条证据保留 anchor、source version、页码和摘要；架构流程与
  扫描工作流两个任务分别附带原始视觉流程图、扫描页 artifact path。
- fixture-contract 验证 context anchor 与任务完全一致、来源版本/页码可解析，且 prompt 不含 XML assertion
  或 expected answer。模型 6/6 返回可解析 draw.io XML，历史 v1 结构断言也为 6/6；但 required citation
  contract（anchor、版本、页码）只有 2/6，历史 v1 contract-completion 为 2/6=33.3%。
- 两个视觉/OCR 任务均只在成功附带合成图片后计分；第一次路径错误产生的 HTTP 400 在模型返回前终止，随后各重试一次。
- **决策**:必需引用契约符合率为 2/6，说明最低输出契约未稳定满足，故该诊断 run 不晋级、不打开
  Validation；不能把 2/6 与计划的 claim-level 0.90 citation-completeness 门槛直接比较。该评分尚未逐条评估
  claim-level completeness、citation precision、claim correctness 或
  faithfulness，因此不能外推为一般性的生成/引用失败。实验只测固定 context 下的 E7 生成/引用，**不是**
  E6 的 raw-top8 对 candidate-top8 比较；仓库内 legacy holdout 未运行。
- 详见 [2026-07-22-e7-gpt-5-5-fixed-development.md](2026-07-22-e7-gpt-5-5-fixed-development.md)。

### E6/E7 pre-next-stage hardening · 2026-07-22 · ✅本地评测契约已修正

- **E6 有效实验臂**:复核发现旧 `e6-drawio-core` 的 47 个单资料 case 上 control/candidate 47/47
  完全相同，故明确退役为 negative diagnostic。E6a 改用已冻结的 26 个 E4b 多资料 Development pool；
  26/26 context 改变，anchor-level gold-evidence recall 0.5096→0.5673，平均来源数 2.46→3.96，超过
  20% effectiveness gate。recall 配对 95% 区间 [0.0000, 0.1346] 触及 0，故只作描述性方向；来源数
  delta 区间 [1.1923, 1.8077]。该结果证明 selector 有效且来源覆盖改善，不冒充晋级或生成实验。
- **真实 draw.io edit**:新增 active v2 任务；结构编辑、版本 pin、两列布局、Reviewer 权限和临时 reviewer
  任务均带 input draw.io XML，并以 stable cell ID、保留 label/attribute、指定变更、禁止 edge 或 geometry
  断言评分。v1 保留作历史数据；其中 repository-visible holdout 不再声称是 unseen final holdout。
- **引用指标**:历史 2/6 重新定性为 `requiredCitationContractRate`，即必需 anchor+version+page 输出契约；
  它不是 claim-level citation completeness/precision。新 evaluator 在没有人工 atomic-claim review 时明确输出
  `claimMetricsStatus=not_evaluated`。
- **运行可复现性**:新增 generation run manifest validator，formal run 必须记录 corpus/task/prompt/response
  SHA-256、provider/model/endpoint、request 参数、冻结 task ID 全覆盖和逐 call request ID、prompt hash、
  attempts、usage、latency。旧 GPT-5.5
  fixed run 仅能回填部分字段，已保存为 `qualification=diagnostic, formalEligible=false`，不追溯伪造成 formal。
- **分区治理**:新增外部 final-holdout contract（至少 20 个紧扣 draw.io 的 creation/edit/visual-OCR/
  version-authorization/recovery task）；题目与答案在最终释放前不得进入本仓库。当前状态为
  `not_materialized`，因此还不能声称已有真正盲测集。
- **外部调用**:本轮只做本地 fixture、selector 与 evaluator；没有发送资料、没有调用模型、没有打开 Validation。
- 详见 [2026-07-22-e6a-chartbook-context-selection.md](2026-07-22-e6a-chartbook-context-selection.md)。

### E6b paired multimodal hydration export · 2026-07-22 · ✅本地 gate 完成，⏸真实 trace 待接入

- **已实现**:`export_drawio_paired_hydration.py` 只接受同一 retrieval run 的 active v2 Development 任务级
  hydration trace，输出每任务一条 control/raw-top8 和 candidate/source-aware-top8 context，并把冻结图册的
  source 范围写入输出，供 prompt builder 再次校验。
- **完整性 gate**:导出要求同一 run 为每个任务提供恰好 top-40 候选（raw top-8 对 source-aware top-8），并拒绝
  缺失/重复 task、非连续 rank、跨图册来源、空 evidence text、缺 anchor/page/source 定位信息、visual/OCR artifact
  路径逃逸或 SHA-256 不符；architecture flow 与 planning scan 任务的两个实验臂还必须都有 verified artifact。若少于
  20% task 的 chunk list 改变，也拒绝产出 E6b 输入。required anchor 仍只归 evaluator 使用，不参与导出选择。
- **如实状态**:这不是一次 E6b/E7 运行。现有 Java live runner 只导出 text/table candidate ID，且未挂载
  architecture visual flow 与 planning scan OCR hydration；因此没有伪造 context、prompt、manifest、模型调用、
  token 消耗或 Validation 结果。
- **下一依赖**:把 production retrieval/hydration trace 扩展为该合同（包括真实 visual/OCR artifact）后，本地导出
  control/candidate JSON，冻结 prompt bundles 与 manifest；再另行取得明确的 Development 模型调用授权。

### E6b real task hydration producer · 2026-07-22 · ✅入口完成，⏸未运行

- **真实路径**:新增 ingestion worker opt-in live test，读取 active v2 Development 的 model-visible task request 与
  frozen 三资料 chartbook，在同一次 run 中为每个 task 查询 top-40；trace 只基于实际 chunk text、source/page 和
  source-page visual/OCR artifact 生成 hydration evidence。未命中 evaluator gold 的 chunk 仍以
  `retrieved:<chunkId>` 形式完整保留；只有精确 source/page/text 对应的 anchor 才改变 citation label。任务的
  evaluator `requiredAnchors`、XML assertions 与 claim universe 不参与查询或选择。
- **OCR/视觉约束**:planning scan 在 canonical chunk 前经过 worker 的 `TesseractOcrEngine`；architecture route 与
  scan 页只使用 fixture 中冻结的原始视觉 artifact，并写入 SHA-256。没有可执行 Tesseract、明确输出路径或 test/dev
  Pinecone namespace 时 live test skip，禁止把 text-only run 误写成 multimodal trace。
- **未运行原因**:本机当前没有 Tesseract executable；而且本轮尚未取得针对这次新的 Pinecone Development trace 的
  运行授权。因此没有上传/删除向量、没有生成 trace、没有调用生成模型，也没有 token 消耗。

### E6b real task hydration · 2026-07-23 · ❌输入 readiness gate 未通过

- **执行**:在临时 `material-rag-e6b-dev-20260723` namespace 运行真实 PDFBox → Tesseract (`eng+chi_sim`) →
  canonical evidence → Pinecone top-40；6/6 Development task 完成，59 个向量已在 finally cleanup 后删除。原始 trace
  SHA-256 为 `aa24952b2720005615658503917e94bfd58c82fed83cd6c64fa68ac535a3f523`。
- **失败一（视觉）**:`dgt-dev-02` 的 top-40 中没有 architecture source 的 page-3 candidate，因此 control/candidate
  都不能携带必需的 request-route artifact；exporter 正确拒绝，未降级成 text-only。
- **失败二（对照）**:source-aware top-8 只改变 `dgt-dev-05`，即 1/6 = 16.67%，低于预注册 20% changed-task gate。
- **结论**:这是 input-readiness / representation failure，不是生成模型质量结论；没有 prompt、模型调用、token usage、
  Validation 或晋级决定。下一步先改善不依赖 evaluator gold 的 architecture visual-route retrieval/hydration，再重跑
  Development trace。详见 [2026-07-23-e6b-task-hydration-run.md](2026-07-23-e6b-task-hydration-run.md)。

### E6b architecture visual projection repair · 2026-07-23 · ✅本地修复完成，⏸待重跑

- **根因**:architecture PDF 第 3 页明确是 raster-only route；旧 task producer 对该 source 调用
  `buildProjection`，其 visual manifest 固定为空，因此只索引 surrounding prose，完全没有第 3 页图的 visual chunk。
- **修复**:task producer 现复用 worker 的 visual-candidate selection 与 `VisualCropDeriver`，把选中的真实 raster
  crop 作为 `VISUAL` evidence/chunk 建入 architecture projection。Figure 2 增加不重复节点顺序的标准 caption，令 visual
  chunk 获得 source-backed retrieval context；新增本地契约测试，要求 page 3 visual chunk 为可检索并包含该 caption；测试通过。
- **边界**:该修复没有用 task required anchor、预期节点或答案来选择图；下一次仍只运行 Development top-40 trace，
  由既有 artifact/contrast gate 决定能否继续。

### E6b visual hydration rerun 1 · 2026-07-23 · ❌输入 gate 未通过，已定位

- **运行**:真实 Development trace `drawiohydrationpdfresearch_9021e47dab45433db7ae53e0c6ca8f89`，commit `916fd9af`；
  61 chunks 建入临时 dev namespace 后删除。无模型调用、无 Validation。
- **结果**:Figure 2 visual chunk 已可检索并带有冻结的 route image，但对 `dgt-dev-02` 排名第 10，E6b 每臂只取
  top-8，故 exporter 正确拒绝缺少 required image artifact 的 paired context。
- **后续**:caption 已改为更准确的任务描述（仍不复述节点顺序或 gold）；重新生成锁定 source 后待重跑同一 Development
  trace。详见 [2026-07-23-e6b-task-hydration-r2-run.md](2026-07-23-e6b-task-hydration-r2-run.md)。

### E6b visual hydration rerun 2 · 2026-07-23 · ❌输入 gate 未通过，路由修订待重跑

- **运行**:真实 Development trace `drawiohydrationpdfresearch_c467437f68a54548acc201520d02b2f0`，commit `6a7cd904`；
  61 chunks 建入临时 dev namespace 后删除。无模型调用、无 Validation。
- **结果**:Figure 2 visual chunk 仍可检索并带有冻结图像，但 broad chartbook dense raw top-8 继续遗漏该 artifact；同时
  layout-only `dgt-dev-06` 明确没有检索上下文，不能被错误地要求 40 个候选。
- **后续**:审查否决了把 selected-source-first 混入 E6b raw control 的方案；保留 no-retrieval task 的两臂空 context
  修复，并先实现真正改变 broad-chartbook visual retrieval 的独立干预后再重跑。

### E6b selected-visual-page OCR representation · 2026-07-23 · ✅Development input gate 通过

- **变量（唯一）**:architecture visual projection 仍先依照 native document structure 的固定 visual-selection
  policy 选择真实图像页；仅对这些已选页调用本地 Tesseract，再以含 OCR 的页面重建 canonical evidence/chunk。
  原始 visual crop/artifact 不变，继续供后续模型判断箭头方向和精确结构。
- **防泄漏**:页选择、OCR 与 chunk build 不读取 task `requiredAnchors`、expected answer、XML assertion 或 evaluator
  gold；raw top-8 的 dense 排序也没有改成 selected-source-first。OCR 只提供从真实页面识别出的检索词，不能替代
  图像本身的结构判断。
- **本地验证**:新增契约测试，使用坐标化 OCR word stub 证明被选的 architecture 第 3 页同时产出可检索 visual
  chunk 与 OCR TEXT chunk；`ControlledPdfDenseRecallLiveTest` 目标测试通过。此前单一大 OCR region 会被 canonical
  overlap 规则与 native caption 合并而不产出检索块，已通过按 OCR 实际 word/line 边界的测试覆盖该风险。
- **真实 Development r4**:在临时 `material-rag-e6b-dev-20260723-r4` namespace 对 5 个需检索 task 运行真实
  PDFBox → selected-page Tesseract → canonical evidence → Pinecone top-40；62 个 synthetic vectors 由 runner 的
  `finally` cleanup 删除。`dgt-dev-02` 的 architecture page-3 OCR companion chunk 升至 raw rank 4 并携带冻结
  request-route image；这让 control 和 candidate 两臂都通过 required-artifact gate。
- **导出与决策**:exporter 生成 6 条 task 的 paired context；显式 layout-only/no-retrieval 的 `dgt-dev-06` 两臂为空，
  未计入 contrast 分母。其余 5 task 中有 3 个 context 改变，**60%** 超过 20% gate。E6b 的 input stage 因而完成，
  但尚无 prompt、模型调用、token 消耗、质量结论或 Validation；下一步冻结 prompt bundle 与 formal run manifest，
  再取得单独的模型调用授权。详见 [2026-07-23-e6b-task-hydration-r4-run.md](2026-07-23-e6b-task-hydration-r4-run.md)。

### E7 r4 paired prompt freeze · 2026-07-23 · ✅本地准备完成，⏸模型授权待获取

- **冻结输入**:从同一 r4 paired hydration 导出 control/raw-top8 与 candidate/source-aware-top8 各 6 个
  Development prompt；每条保留 task request、适用的 editable input XML、evidence text、citation location 和
  验证过的 visual/OCR artifact path。两份 bundle SHA-256 分别为
  `ed38cfda2bd3a2d6ffe3793bcf094ab4fa9ae4916b0df4d29ec31d2b3ded74a0` 和
  `897dd3dd6e63d961177f23c36b471833bfedbd0b8d7d77c9cb7ff96a61e1dc51`。
- **边界检查**:builder unit tests 通过；prompt 不渲染 evaluator-only 的 `requiredAnchors` 或 expected answer。
  `dgt-dev-02` 两臂都附带 architecture route image，`dgt-dev-06` 依合同没有 evidence/image。
- **未发生事项**:未向任何 provider 发送 bundle；因此还没有 response、token usage、cost、formal manifest、评分、
  晋级或 Validation。取得对同一 provider/model 的 12 个 synthetic Development request 的明确授权后，才会为每个 arm
  生成独立 formal manifest 并运行 E7 paired evaluation。详见
  [2026-07-23-e7-r4-development-prompt-bundles.md](2026-07-23-e7-r4-development-prompt-bundles.md)。

### E7 r4 — GPT-5.5 paired Development generation · 2026-07-23 · ❌不晋级

- **正式运行**:control/raw-top8 与 candidate/source-aware-top8 各发送 6 个 synthetic Development prompt 到
  OpenAI GPT-5.5；两臂 formal manifest 均通过 validator（prompt/response/task/corpus hash、逐 call request ID、
  usage、latency 完整）。合计 12/12 HTTP 200、54,795 input + 12,685 output tokens、139,713 ms；按标准文本单价
  粗估约 **$0.65**，不含税或账户差异。Validation 与 holdout 均未发送。
- **结果**:两臂 XML parse 都是 6/6，required citation contract 都是 1/6，complete task 都是 0/6；因此没有
  可归因给 source-aware selector 的差异。`dgt-dev-02` 两臂均能生成四节点 editable request route，说明 r4 的
  visual/OCR artifact 已真实进入模型上下文，但模型把 `scope`/`scope_sources` 等生成 cell ID 当 citation，而非
  evidence 中的 `daa-route-scope` 等精确 anchor，评测器正确拒绝。
- **解释边界**:唯一 citation pass 是不要求 citation 的 layout-only `dgt-dev-06`，它的 geometry-only edit assertion
  仍失败。独立双 reviewer claim review 尚未做，故 claim correctness、faithfulness、citation precision/completeness
  明确为 `not_evaluated`。这是一项 prompt-output contract failure，不是对 RAG retrieval 质量的负面结论。
- **决策**:不晋级、不打开 Validation。下一步先预注册并本地测试 citation-output contract repair——要求模型仅从
  supplied evidence 的精确 `anchorId` 选择 citation、不得使用 diagram cell ID；冻结新 bundle 后才可另行授权重跑
  Development。详见 [2026-07-23-e7-r4-gpt-5-5-development.md](2026-07-23-e7-r4-gpt-5-5-development.md)。

### E7 r5 — citation-output contract repair and Development freeze · 2026-07-23 · ✅本地完成，⏸模型授权待获取

- **单一变量**:r5 从模型已可见的 hydrated evidence 行确定性导出 `citationOptions`，在 prompt 中列出唯一允许的
  `anchorId`/`sourceVersion`/page 三元组，并明确禁止使用 mxCell/XML/label/generated diagram ID。它不读取
  `requiredAnchors`、expected answer 或 XML/edit evaluator assertion；检索、r4 hydration、两臂 context、任务、
  模型参数和 evaluator 都不变。
- **双重执行契约**:GPT structured-output schema 的每个 citation 仅接受 bundle 中可见 evidence 的完整三元组；无检索
  task 强制空 citations。runner 的本地 response boundary 独立验证同一三元组，因此即使 anchor 名称正确但页码或版本错误
  也会作为 failure，不能因模型返回 JSON 而绕过。
- **冻结与测试**:control 6-task bundle SHA-256 为
  `d0ca377caac7de398cc17995991544e551cb44c385770761ecfa7d6eeb1ae768`，candidate 为
  `51ff96d79847946530135f4ff40e30ce53c04b49c9aef51e664659d8fa598b87`。91 个 analysis 本地测试通过；无 provider
  调用、token、Validation 或 holdout 访问。
- **下一边界**:取得新的明确授权后，才向相同 GPT-5.5 endpoint 发送这 12 个合成 Development request，并用 formal
  manifest 和未变 evaluator 比较；仍不得把结果直接解释为 retrieval 差异。详见
  [2026-07-23-e7-r5-citation-contract-repair.md](2026-07-23-e7-r5-citation-contract-repair.md)。

### E7 r5 — GPT-5.5 paired Development generation · 2026-07-23 · ❌不晋级

- **正式运行**:control/raw-top8 与 candidate/source-aware-top8 各 6 条 synthetic Development request 均为 HTTP
  200；两臂 formal manifest 均 `valid=true`、`formalEligible=true`。记录的 12 calls 合计 65,217 input + 13,140
  output tokens、148,415 ms，按标准价粗估 **$0.72**。首次 candidate 进程未写 response/manifest，无法形成 formal
  artifact；经用户明确允许后只补发一次 candidate，当前 manifest 记录的是可复现的 12 calls。Validation/holdout
  未发送。
- **结果**:两臂 XML parse 都为 6/6、required citation contract 都为 1/6、completed task 都为 0/6。r5 的完整三元组
  schema/local boundary 已生效：返回的非空 citation 都来自模型可见的 `citationOptions`，不再出现 cell ID。
- **根因**:五个需检索任务的 evaluator-required anchor 均不在各自 top-8 的 model-visible citationOptions 中；模型只
  能引用 alternate/fallback `retrieved:<chunkId>` evidence 或留空。`dgt-dev-02` 的 page-3 OCR chunk 已进上下文但仍为
  fallback ID，未保留 `daa-route-scope`/`daa-route-compose`；这是 input identity-propagation/evidence-availability
  failure，而非模型不遵守 r5 输出契约或 selector 的质量结论。
- **决策**:不晋级、不打开 Validation。下一变量必须是本地预注册的 citation-identity/hydration repair：不读取 task
  required anchor 或 expected answer，但让 selected source/page 的可验证 evidence span 保留 canonical anchor；先通过
  model-visible required-evidence readiness gate、冻结新 Development bundles，再另行请求模型授权。详见
  [2026-07-23-e7-r5-gpt-5-5-development.md](2026-07-23-e7-r5-gpt-5-5-development.md)。

### E7 r6 — citation-identity safety boundary and readiness gate · 2026-07-23 · ❌input readiness gate 未通过

- **安全边界**:fallback `retrieved:<chunkId>` 不会由 exporter 依据 `ground-truth.json`、task required anchor、expected
  answer、OCR 相似度或 ranking signal 猜测为 canonical anchor；只有 ingestion trace 自身未来持久化的 source-evidence
  identity/span 才可提供 canonical citation。这避免 evaluator gold 进入模型可见上下文。
- **readiness 决策**:r4 frozen trace 的 5 个 retrieval-required task 均只保留 fallback identifier，因而均在两臂缺少
  required canonical evidence。`--require-model-visible-required-evidence` 如预注册非零退出；prompt builder 与 runner
  也强制拒绝未通过 gate 的 context/bundle。未来 bundle 会绑定 hydration export 的 path/SHA-256，runner 在任何请求前重新
  校验该 export 的 `ready`、task/arm 和 model-visible evidence，不能仅靠手工 `true` 字段绕过。没有 Pinecone、provider
  token、Validation 或 holdout。
- **下一步**:不运行 r6 generation。须预注册 ingestion source-identity persistence 与 retrieval/hydration
  evidence-availability intervention，保留 r6 readiness gate，在本地通过后才可冻结新 Development bundle。详见
  [2026-07-23-e7-r6-citation-identity-readiness.md](2026-07-23-e7-r6-citation-identity-readiness.md)。

### E7 r7 — source-evidence identity persistence · 2026-07-23 · ❌Development input readiness 未通过

- **单一变量**:新的 publisher-owned source identity manifest 在资料侧声明 `sourceEvidenceId`、source/version/page 与
  exact-text 或 VISUAL-page match；hydration 的 identity-assignment path 只读该 manifest，而不读取 task、
  `ground-truth.json` 或 expected answer。未匹配 evidence 继续使用 `retrieved:<chunkId>`。
- **真实运行与清理**:授权后在临时 `material-rag-e7r7-dev-20260723` namespace 运行真实 PDFBox → Tesseract →
  canonical evidence → Pinecone top-40；106 个本次向量在 `finally` cleanup 中删除。trace 绑定 commit
  `81a2824d`、corpus-lock 与 source identity manifest hash；没有模型调用、Validation 或 holdout。
- **gate**:paired context 的 5/5 retrieval-required task 改变（100%，超过 20% contrast gate），视觉 artifact 也通过；
  但 model-visible required-evidence gate 未过。`dgt-dev-01` control 缺 handoff/sequence（canonical chunk rank 12），
  `dgt-dev-03` control 缺 canonical/factual-edit（rank 21），`dgt-dev-04` 两臂均缺 version-pin（rank 38），
  `dgt-dev-05` 两臂均缺 budgets（rank 40）。
- **结论**:source-owned identity 已在 top-40 正确解析，失败是证据排名/availability，不能解释成身份映射或模型质量失败。
  按预注册不冻结 prompt、不调用 GPT-5.5、不打开 Validation。下一变量必须在不读取 evaluator ground truth、required anchor
  或 expected answer 的前提下，改善 source-side retrieval/ranking 或 evidence availability。详见
  [2026-07-23-e7-r7-development-hydration-run.md](2026-07-23-e7-r7-development-hydration-run.md)。

### E7 r8 — page-parent evidence availability · 2026-07-23 · ❌Development 多模态输入合同未通过

- **单一变量**:在已有细粒度 leaf 之外增加可检索、可引用的 `PAGE_PARENT`；每个 parent 只含同一 source page 的
  canonical text Evidence、原 page ID 与全部 copied Evidence ID。超过 900 tokenizer units 时分为连续同页 parent，
  不截断、不跨页混合。它不同于 E2 的邻居窗口：E2 改 leaf embedding text，r8 新增独立的页级 projection。
- **安全边界**:active Development task 显式提供 agent-visible `selectedMaterialVersion`，producer 仅验证其为已挂载版本，
  不据此过滤 chartbook sources 或改变排名，以保持 r8 只有 page-parent 一个变量；parent 构建与 source identity
  resolver 均不读取 `requiredAnchors`、expected answer、claim/XML assertion 或 `ground-truth.json`。exact-text/
  visual-page identity 与 r6 gate 保持不变。
- **本地核验与审查修正**:P1 审查后，parent 改为直接读取 source-page text Evidence，避免 leaf 为检索便利附带的跨页
  heading/table header 进入页级引用；TDD 覆盖 source-page locality、超过 900 的连续拆分、citable/searchable 状态和
  copied Evidence ID，并在实际 PDF/OCR projection 中确认 selected visual page 的 parent。另覆盖空或未挂载 selected
  material 直接拒绝。无 Pinecone、模型、Validation 或 holdout。
- **真实运行与清理**:授权后在临时 `material-rag-e7r8-dev-20260723` namespace 运行 PDFBox → Tesseract →
  canonical evidence → Pinecone top-40；80 个本次向量由成功完成的 live test 在 `finally` 中删除。trace 绑定
  `b0e06ce5`、当时的 corpus-lock 和 source identity manifest；没有模型调用、Validation 或 holdout。
- **gate**:raw source-aware selector 改变 4/5 retrieval-required task（80%），但 paired exporter 在生成 context 前
  拒绝 `dgt-dev-02`：architecture page-3 的 visual/OCR artifact 在 R8 raw top-8 与 candidate top-8 均缺失（text rank
  22、VISUAL rank 24；r7 对应为 rank 4、3）。因此是多模态输入合同失败，尚未执行 model-visible-required-evidence
  gate，不能冻结 prompt 或调用 GPT-5.5。
- **结论**:R8 不晋级。下一变量必须在不读取 evaluator anchor、expected answer 或 XML claim 的前提下恢复必要的
  visual/OCR artifact availability。详见 [2026-07-23-e7-r8-development-hydration-run.md](2026-07-23-e7-r8-development-hydration-run.md)。

### E7 r9 — visual-safe page-parent routing · 2026-07-23 · ❌Development 多模态输入合同未通过

- **单一变量**:保留 `PAGE_PARENT` 的 source-page provenance、citation 与 lexical projection，但将其 index mode 改为
  `LEXICAL_ONLY`，不再进入 dense candidate pool。R8 的可检索页级文本不再直接挤占 visual/OCR vector 的 dense 名额。
- **安全边界**:不读取 task 的 required anchor、expected answer、XML assertion、evaluator ground truth、模型输出或
  selected source version；leaf、visual/OCR、source identity、query、chartbook scope、ranker 与 top-8 budget 均不变。
- **本地核验与审查修正**:TDD 先证明原 parent 为 dense 会失败；随后验证 parent 仍 citable 且存在 lexical projection、但实际
  PDF/OCR projection 的 parent 为 lexical-only。P1 审查后将 `page-parent-lexical-only` 写入 processing fingerprint，避免
  沿用 R8 的 projection profile；同时让 trace 的 lexical candidate 导出直接解析所有 searchable chunk。hybrid lane 保留
  lexical-only parent 的原 RRF 顺序和 source/page provenance，只有该 parent 不被误当成 Pinecone vector；无网络回归从
  实际 PDF/OCR projection 构建 dense index，执行 lexical rank 与 RRF，覆盖 parent 不进 dense candidates、但仍进入
  lexical/hybrid hydration。domain 12 项和 worker 14 项定向测试通过（5 项 opt-in Pinecone tests 按设计跳过）。无
  Pinecone、模型、Validation 或 holdout。
- **真实运行与清理**:授权后在临时 `material-rag-e7r9-dev-20260723` namespace 运行 PDFBox → Tesseract →
  canonical evidence → Pinecone top-40；62 个本次向量由成功完成的 live test 在 `finally` 中删除。trace 绑定
  `3136c759`、R9 运行时 corpus-lock 和 source identity manifest；没有模型调用、Validation 或 holdout。
- **gate**:`dgt-dev-02` 的 raw control top-8 缺 architecture page-3 visual/OCR artifact，因此 paired exporter 在生成
  context 前停止。对应 VISUAL 为 rank 28、TEXT 为 rank 34，较 R8（24、22）更差；尚未进入 contrast 或 r6 readiness。
- **结论**:R9 不晋级。详见
  [2026-07-23-e7-r9-development-hydration-run.md](2026-07-23-e7-r9-development-hydration-run.md)。

### E7 r10 — embedding-input provenance · 2026-07-23 · ✅本地 trace 合同完成

- **动机**:R8→R9 少了 18 个 dense page parent，且 `dgt-dev-02` 的 query 与 architecture page-3 VISUAL chunk ID 不变，
  但 VISUAL rank 由 24 变为 28。旧 trace 未持久化 embedding input/model identity，不能把此差异归因于页面 parent routing。
- **单一变量**:未来 retrieval result 与 hydration trace 持久化 provider、model、vector dimension，以及按顺序构成的
  passage/query 输入 SHA-256。passage 的实际 embedding 顺序固定为 source version、chunk ID；query 覆盖 ORIGINAL 与
  EVIDENCE_FOCUSED 两个真实请求序列。不保存 embedding vector、凭证、evaluator anchor、expected answer、XML assertion 或模型输出。
- **本地核验**:同一输入稳定；只改变或重排 passage、只改变 evidence-focused query 时都会改变对应 hash。worker 16 项定向测试
  通过（5 项 opt-in Pinecone tests 按设计跳过）。无 Pinecone、模型、Validation 或 holdout。
- **使用约束**:后续视觉/OCR retrieval intervention 的 trace 必须含此 manifest；只有 model 与两类 input hash 一致的重复臂
  才能解释为 ranking effect。详见
  [2026-07-23-e7-r10-embedding-input-manifest-pre-registration.md](2026-07-23-e7-r10-embedding-input-manifest-pre-registration.md)。

### E7 r11 — visual text-context representation · 2026-07-23 · ❌r6 model-visible-evidence gate 未通过

- **动机**:R9 的 `dgt-dev-02` 在导出上下文前失败：architecture page-3 的 VISUAL/OCR artifact 不在 top-8
  （VISUAL rank 28、TEXT rank 34）。诊断发现 VISUAL chunk 的 dense 表示实质是 caption，而同页流程词由独立 OCR TEXT
  chunk 承载，未进入 visual representation。
- **单一变量**:对每个 VISUAL Evidence，只在完整文本仍不超过既有 420-token 上限时追加同页非空 OCR TEXT，或空间落在
  visual region 内的 NATIVE TEXT；VISUAL 保持 `PRIMARY` citation、caption 保持 `CAPTION`，每条追加文本以 `CONTEXT`
  mapping 明示。跨页 OCR 与同页 visual 外 native text 均不会加入。fingerprint 为
  `visual-same-page-text-context-v2`，让未来 trace 可与 R9 区分。
- **安全边界**:不读取 task ID、required anchor、expected answer、XML/edit assertion、evaluator ground truth、selected
  source version、模型输出或当前 rank；不改变 source identity、chartbook scope、ranker、top-8 budget 或 citation boundary。
- **本地核验与审查修正**:初版只选择 `sourceChannel=OCR`，但真实 architecture PDF 的选中 OCR 会在 canonicalization
  中被更强 native label 吸收，worker seam 因而没有 context、独立审查报告 P1。修订为同页 OCR 或空间重叠的 native text
  后，`RetrievalChunkBuilderTest` 13/13、`ControlledPdfDenseRecallLiveTest` 16/16 通过（后者 5 项 opt-in Pinecone
  tests 按设计跳过）；ingestion-worker reactor main-code package build、Python analysis 97 项也通过。无 Pinecone、模型、
  Validation 或 holdout。
- **真实运行与清理**:授权后从隔离的 `22152510` worktree 在临时
  `material-rag-e7r11-dev-20260723` namespace 运行 PDFBox → Tesseract → canonical evidence → Pinecone top-40；
  成功 run 索引 106 个向量，`finally` 删除并等待清理完成。首次 run 因本地 commit-provenance 环境变量缺失而在写 trace 前
  停止，但 retrieval cleanup 已完成；成功的第二次 run 写入 trace。全程无模型、Validation 或 holdout。
- **gate**:`dgt-dev-02` 的 route visual/OCR artifact 现在在 control/candidate top-8 均可见，multimodal artifact
  contract 通过；paired selector 改变 5/5 retrieval-required task（100%，有效实验）。但 r6 model-visible gate 仍失败：
  control 缺 `dgt-dev-01` 的两个 anchor、`dgt-dev-03` 的两个 anchor、`dgt-dev-05` 的 budget anchor，candidate 也缺
  `dgt-dev-05` budget。因此不冻结 prompt，不调用模型，不打开 Validation。详见
  [2026-07-23-e7-r11-development-hydration-run.md](2026-07-23-e7-r11-development-hydration-run.md)。

---

## 当前状态与下一步

- 已完成:E0 基线 → E1 表示层改进 → 核心集升级并冻结到 450 → Development 与 Validation
  配对重跑 → 守门最低数补齐并执行 fixture-contract。Validation 证明 E1 提升真实,也证明
  dense-only 尚未达门槛。
- **下一步**:R11 已修复 architecture route visual availability，但完整 r6 model-visible-evidence gate 未通过；下一变量
  必须预注册为不读取 evaluator 数据的 broader evidence-availability intervention。Validation 暂不打开。

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
- [x] E6 selector 与 E7 XML/citation evaluator——已实现，并完成一次仅 Development 的 response-contract pilot；
  该 pilot 不含证据正文，不能作为 E6/E7 质量结论。
- [x] E6a 多资料 selector effectiveness——26/26 context 改变，retrieval 指标已记录；不含模型调用。
- [x] active v2 真实 edit fixture、引用指标分层与 generation-run manifest contract。
- [x] E6b paired hydration export contract——r4 visual/OCR trace、artifact/source/contrast gates 与审计记录已完成。
- [x] E7 r4 evidence-grounded control/candidate Development 比较——已运行但 citation contract 1/6、task completion 0/6，未晋级。
- [x] E7 r5 citation-output contract Development 比较——模型不再输出 cell ID，但 required canonical anchor 未进上下文，
  1/6 citation、0/6 completion，未晋级。
- [x] E7 r6 citation-identity safety boundary/readiness gate——不以 evaluator gold 推断 identity，5/5 grounded task
  gate 未过；未发送模型请求。
- [x] E7 r7 source-evidence identity persistence——source-owned manifest、exact-match/visual-page contract 与真实 trace hash
  已完成；5/5 contrast 通过但 model-visible required-evidence gate 未过，未发送模型请求。
- [x] E7 r8 page-parent evidence availability——Development trace 已运行并清理 80 个临时向量；因 `dgt-dev-02`
  required visual/OCR artifact 不在任一 top-8 而止步，未发送模型请求。
- [x] E7 r9 visual-safe page-parent routing——Development trace 已运行并清理 62 个临时向量；`dgt-dev-02` control top-8
  仍缺 required visual/OCR artifact（VISUAL rank 28、TEXT rank 34），未发送模型请求。
- [x] E7 r10 embedding-input provenance——未来 trace 记录 embedding model、dimension、ordered passage/query input hash；
  仅本地测试，未发送模型请求。
- [ ] 下一项 source-independent visual/OCR availability intervention——需预注册、TDD 和新的 Development artifact gate；
  通过全部 gate 前不调用模型。
- [ ] 外部 final holdout——contract 已定，payload 尚未由独立保管人生成和隔离。

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

### R12 — evaluation-contract repair and v3 draw.io task expansion · 2026-07-23 · ⏸ 等待双人审阅

- **审阅治理修复**:旧 `material-rag-review-ledger-v1` 允许把 AI reviewer 与一名人类计作 double review。
  新 v2 ledger 只有在两个不同注册人类 reviewer 各自提交完整 `material-rag-human-review-v1` 文件后才计数；
  auditor 校验相对路径、SHA-256、reviewer ID、450 个 core case 的精确覆盖以及逐 case verdict。当前 ledger
  未绑定两个人类文件，因此 E0 readiness 正确保持 BLOCKED，corpus-lock 为 candidate。
- **历史结论勘误**:此前依赖旧 ledger 的“正式”与 Validation 结论全部降级为 provisional diagnostic；
  已归档的 dated result/lock 不改写。R11 可在 commit `22152510` 恢复原锁，SHA-256 为
  `872642afc468ce94e741ef7374c6f9752885ef91b6d1e941600d3acde0cc6db4`。R8 page-3
  text/VISUAL rank 为 22/24，R9 为 34/28，属于不同 chunk/channel。
- **v3 Draw.io 核心任务**:`drawio-generation-tasks-v3.json` 扩为 20 Development + 20 Validation，
  Development 覆盖 7 个资料家族，Validation 覆盖 5 个隔离家族；任务包含资料生成图、结构编辑、流程/时序、
  OCR/扫描转 editable XML、来源范围、版本、权限、阈值和降级状态。v2 保留作历史复现。
- **来源范围**:统一 `selected_only`、`chartbook_auto`、`none` 合同。producer、exporter 与 prompt builder
  都按同一 mounted scope 计算；越权候选直接拒绝，不再静默过滤。
- **readiness**:candidate 必须具备全部 required evidence 与多模态 artifact；control 缺失作为 baseline
  测量结果保留。这样既不会因 control 较弱而阻止实验，也不会让不完整 candidate 调用模型。
- **引用安全**:模型只见 `CIT-###` opaque handle、source version 和 page。私有 resolution map 在响应后
  解析 canonical source-evidence ID；evaluator anchor 不进入 prompt。
- **本轮外部使用**:0 次 Pinecone、0 次模型请求、0 个 Validation/holdout case；只生成本地 fixture、
  identity manifest、readiness 与测试产物。

#### R12 后续固定流程

1. 两名人类独立完成 450-case review artifact；任何 `needs_fix` 先修 fixture 后重新审阅。
2. 重跑 E0 audit，要求 `READY`、`double_reviewed` 和 frozen corpus-lock，然后提交固定 commit。
3. 在临时 Pinecone test/dev namespace 跑 v3 Development hydration，保存 provenance/embedding manifest，
   并在 `finally` 删除本次向量。
4. 通过 scope、artifact、candidate readiness、20% paired contrast 后，冻结两臂 prompt。
5. 运行同一模型的 20+20 Development；做 XML/edit/citation 自动评测和双人 claim review。
6. Development 晋级后才开一次 Validation；通过后依次跑 E8、E9。
7. 全部配置冻结后由独立保管人生成仓库外 final holdout，并且最多运行一次。

### R13 — owner spot-check governance · 2026-07-23 · ✅E0 解锁

- **用户决策**:项目负责人确认可以由 Codex 做全量自动审阅，并在查看代表样例后批准继续。
- **冻结样例**:`controlled-130`、`controlled-624`、`dgt-dev-03`、`dgt-dev-10`、
  `dgt-dev-17`、`dgt-val-04`、`dgt-val-17`。它们覆盖流程箭头、无答案、稳定 cell/edge 编辑、
  OCR/扫描转 editable XML、安全图例、Validation 新流程和权限矩阵。
- **可审计记录**:`owner-spot-check-policy-v1.json` 与 `owner-spot-check.json` 分别保存并由
  ledger v3 绑定 SHA-256。auditor 不只信 ledger，独立重验 policy、artifact、reviewer kind、
  required IDs 与 approve decision。
- **声明边界**:本方法为 `automated-full-owner-spot-check-v1`，不是 independent double-human review。
  lock 明确记录 `independentHumanReview=not_claimed`；后续报告必须保持该措辞。
- **决策**:450/450 自动合同审计与 owner sample gate 均满足；允许冻结新的 corpus-lock 并进入
  v3 Development hydration。Validation、模型调用与 final holdout 仍未打开。

### R13 trace / R14 visual-coverage preregistration · 2026-07-23 · ⏸ 修复后重跑

- **固定输入**:R13 trace 来自 clean commit `5c24c10a13d4f41a584c96e270022eba45f6f88f`，
  split=`development`、canonical=`e1-v5`、chunk=`flat-leaf-v1`、真实 Tesseract 与生产一致 tokenizer。
- **真实管线**:19 个检索任务、186 chunks、embedding token p50=105/p95=338/max=380；第 20 个
  `sourceScopeMode=none` 任务按合同不检索。Pinecone 临时 namespace 的本次向量已由 runner 明确删除。
- **首次 gate**:`dgt-dev-10` 所需 planning scan page 5 artifact 位于 raw rank 19，未进入旧 candidate top-8；
  因此 exporter 在 prompt/model 前退出。
- **第二层原因**:加入不读 gold 的 distinct-artifact reservation 诊断后，`dgt-dev-12` 仍失败。其 datacenter
  page 3 visual artifact 根本未进入 top-40；原 visual policy 对 7 页资料按 15% 只选择 2 页。
- **R14 预注册变量**:controlled chartbook 在 12 页硬上限内覆盖全部本地检测视觉页；multimodal candidate
  top-8 保留 top-3 distinct image artifacts；control 缺 artifact 作为 baseline 测量，candidate 继续 fail-closed。
  两项选择都不读取 task answer、required anchor、XML assertion 或 Validation。
- **外部使用**:1 次 Pinecone Development trace，临时向量已清理；0 次模型请求、0 model token、
  0 Validation/holdout case。

### R18 indexed-vector trace + paired gate · 2026-07-23 · ❌ candidate 未晋级

- **固定输入**:clean commit `ce917431`，Development only；`sourceIndexedVectorCounts` 与实际 Pinecone
  upsert 口径一致。
- **真实运行**:18 个 chartbook-auto task 40/40，`dgt-dev-20` 28/28；一次 rewritten empty query
  重试恢复；19/19 查询完成并删除全部临时向量。
- **已通过 gate**:20/20 task coverage、commit/corpus-lock/embedding provenance、source scope、
  artifact path/SHA、scoped pool completeness、paired contrast。18/19 检索任务改变，94.74%。
- **最终失败**:`modelVisibleRequiredEvidence.ready=false`。candidate 仅 `dgt-dev-03`、`dgt-dev-11`
  完整（2/19）；control 有 `dgt-dev-02/05/10/11` 完整（4/19），candidate 反而更差。
- **故障分层**:14/19 的全部 required canonical evidence 已在 raw top-40，主要是 selector 丢失；
  `dgt-dev-07/08/12/14/15` 的 publisher canonical identity 在 raw top-40 仍不完整。
- **决策**:`source-aware-with-artifact-coverage-top8-v1` 不晋级；未构建 prompt、未调用模型。
  R19 先分开修复 publisher identity availability 与 relevance-preserving selector，再用同一 Development
  gate 比较。Validation/holdout 继续关闭。

### R19a verified visual identity binding · 2026-07-23 · ✅ raw top-40 gate

- **定位结果**:`dgt-dev-07/12/14/15` 的相关 source/page 已在 raw top-40，且仓库中存在对应冻结原图，
  但 TEXT projection 不能继承 publisher `visual_page` identity；`dgt-dev-08` 则是相关 handbook page-2
  text chunk 未进入本次 top-40，必须留给独立 retrieval 变量。
- **唯一变量**:当且仅当 source/page artifact registry 返回真实常规文件时，允许该页 TEXT projection
  绑定 publisher `visual_page` identity；同时登记已存在的 handbook page-4 planning-route 原图。
- **防泄漏**:resolver 与 registry 不读取 task、required anchors、expected answer 或 Validation；
  exact-text identity 仍必须匹配 publisher 文本，禁止 page-only 兜底。
- **预期 gate**:raw top-40 canonical availability 至少从 14/19 提升到 18/19；candidate readiness
  未达 19/19 时不构建 prompt、不调用模型、不打开 Validation。
- **本地诊断**:在不改写 R18 trace 的前提下，按提交 `7d597ed8` 的 source/page registry 与 publisher
  visual identities 重新计算，raw top-40 canonical availability 为 18/19；唯一剩余缺口是
  `dgt-dev-08` 的 `dwh-explicit` 与 `dwh-auto-scope`。该结果仅验证绑定逻辑，不替代新的 Pinecone trace。
- **正式运行**:用户明确授权 Development 临时向量后，从 clean commit `a4924c84` 运行。第一次启动遗漏
  冻结 tokenizer，产生 283 chunks，识别为混入第二变量后仅作无效诊断并清理；未保留为正式 trace。
  随后显式加载 SHA-256=`62c24cdc…5626` 的 tokenizer 重跑，得到与 R18 相同的 186 chunks、
  passage/query hashes 与 token 分布。
- **正式结果**:18 个 chartbook-auto task 为 40/40，selected-only 为 28/28；raw top-40 publisher
  canonical availability 为 19/19。两个运行的临时 Pinecone vectors 均由 runner 明确删除。
- **下游 gate**:旧 selector 的 control 完整 13/19、candidate 完整 12/19，因此仍未构建 prompt、未调用
  模型；Validation/holdout 继续关闭。

### R19b publisher-identity lexical selector · 2026-07-23 · ⏳ 待正式重跑

- **唯一变量**:raw top-8 上最多预留 6 个与 model-visible request 存在 exact/prefix token overlap 的
  publisher identity group；按 overlap 与 raw rank 排序，替换 tail 后恢复原 rank。
- **防泄漏**:只读取 request 和 source-owned `sourceEvidenceId`；不读取 task target source、required
  anchors、assertions、expected answer、ground truth 或 Validation。fallback retrieved ID 不参与匹配。
- **Development 诊断**:12/19 task 改变（63.16%），required-evidence 19/19，7 个 multimodal artifact
  task 全部通过 source-matching artifact gate。
- **决策边界**:实现和独立测试通过后只对已冻结 R19a trace 导出；正式 gate 失败则不生成 prompt或调用模型。
- **实现状态**:commit `67d56413` 已完成 selector、合同与测试；25/25 exporter 专项测试及 122/122
  analysis 全套测试通过，E0 audit 仍为 READY。
- **本地结果**:对 R19a trace 的确定性诊断为 candidate 19/19、control 13/19、changed 12/19，
  artifact 7/7。由于实现与合同刷新了 corpus lock，旧 trace 不能冒充新 commit 的正式输入；必须从新的
  clean commit 重跑同一 Development producer 后才能写正式 paired artifact。
- **外部状态**:尚未获得 R19b 新 commit 的临时 Pinecone 重跑授权；新增模型调用仍为 0，
  Validation/holdout 保持关闭。

### R19b formal gate / R20 bilingual query preregistration · 2026-07-23 · ❌ R19b 未晋级

- **固定输入**:clean commit `b26c0682`，冻结 tokenizer，186 chunks；18 个 chartbook-auto task
  40/40，selected-only 28/28。一次 original query 空结果经 retry 恢复，临时向量已删除。
- **R19b 结果**:15/19 task 改变（78.95%）；control 完整 3/19，candidate 完整 16/19。
  `dgt-dev-16/17` 是 selector loss；`dgt-dev-13` 的 `dcc-threshold` 不在 raw top-40，使 raw canonical
  availability 只有 18/19。
- **决策**:R19b 不晋级；未生成 prompt、未调用模型。不能再用 selector 掩盖 raw recall 缺口。
- **R20 唯一变量**:Latin request 命中冻结 Draw.io 领域短语时，为 evidence-focused rewrite 追加中英
  retrieval terms，覆盖 threshold/human review/escalation、vector/object storage/canvas save、
  incident/SEV。query 以外的 embedding、ranking、selector、task 和 gate 不变。
- **防泄漏与 gate**:不读取 target source、required anchors、assertions、answers、ground truth 或
  Validation。正式目标为 raw 19/19、candidate 19/19、artifact 7/7；未满足前模型保持关闭。
- **实现状态**:commit `b49f4443` 已实现 `drawio-bilingual-evidence-focused-v2` 与 5 个 rewrite
  合同测试；ingestion-worker 90 个测试通过、6 个 live test 按环境正常跳过。正式 Pinecone R20 尚未运行。

### R17 trace / R18 indexed-vector denominator · 2026-07-23 · ⏸ 修复后重跑

- **固定输入**:clean commit `b419a70a`，Development only；两次空查询均经 R16 retry 恢复，19/19 完成，
  临时向量已删除。
- **artifact registry**:producer 已能按 source/page 写入 datacenter、payment、field-audit 等冻结原图；
  source-diverse selector 与路径/SHA gate 保持启用。
- **scoped-pool gate**:`dgt-dev-20` 返回 28，manifest 却期望 35。35 是该 source 的全部 projection chunks，
  其中 7 个 lexical-only 从未 upsert 到 Pinecone；实际 dense indexed vectors 正是 28。
- **R18 预注册变量**:manifest 改为逐 source `sourceIndexedVectorCounts`，与实际 upsert 的
  `DENSE_AND_LEXICAL` 过滤完全一致；exporter 使用该字段计算精确 scoped pool。
- **外部使用**:1 次 Pinecone Development trace，临时向量已清理；0 次模型请求、0 model token、
  0 Validation/holdout case。

### R16 trace / R17 artifact-registry preregistration · 2026-07-23 · ⏸ 修复后重跑

- **固定输入**:clean commit `2e74d385`，Development only；所有自动图册任务 40/40，
  `dgt-dev-20` 28/28。
- **重试实证**:一个 rewritten query 首次空结果，1 秒后恢复；19/19 完成，临时向量已删除。
- **artifact gate**:`dgt-dev-02` 的 architecture page 3 原图位于 raw rank 31，但旧 source/page registry
  未能在 source-diverse reservation 中稳定保留；检查还发现 datacenter、payment、field-audit 冻结原图未注册。
- **R17 预注册变量**:补齐 5 个 active Development 多模态 source 的 source/page→frozen artifact registry；
  candidate 最多保留 4 个 distinct artifact，先保证 artifact-bearing source 多样性，再按排名补满。
  不读取 task 目标源、gold、XML assertions 或 Validation。
- **外部使用**:1 次 Pinecone Development trace，临时向量已清理；0 次模型请求、0 model token、
  0 Validation/holdout case。
- **下一步**:更新 corpus lock 并提交新的 clean commit，从该 commit 重跑 Development trace；旧 R13 trace
  保留为诊断证据，不用于正式 generation。

### R14 trace / R15 scoped-pool preregistration · 2026-07-23 · ⏸ 修复后重跑

- **固定输入**:clean commit `42380d28`，R14 source-independent visual coverage，Development only。
- **运行结果**:19/19 查询执行，186 chunks；Pinecone 临时向量已明确删除。
- **输入 gate**:`dgt-dev-04=0`、`dgt-dev-12=0`，属于异常空结果；`dgt-dev-20=28`，其
  `selected_only` 唯一资料实际只有 28 chunks。旧 exporter 统一要求 40，不能正确区分两者。
- **R15 预注册变量**:producer 写入 mounted-source projected chunk counts；exporter 要求每个任务精确
  `min(40, allowed-source chunks)`。不改变 embedding、query、ranking、selector、task 或视觉覆盖。
- **外部使用**:1 次 Pinecone Development trace，临时向量已清理；0 次模型请求、0 model token、
  0 Validation/holdout case。
- **决策**:R14 trace 仅诊断；提交 R15 后从新 clean commit 重跑。0/40 必须继续失败，28/28 才可通过。

### R15 trace / R16 empty-query preregistration · 2026-07-23 · ⏸ 修复后重跑

- **固定输入**:clean commit `d71f8e04`，Development only，trace 已记录 7 个 mounted source 的
  projected chunk counts（总计 247；本次索引 186 个去重向量）。
- **scoped pool**:17 个自动图册任务为 40/40，`dgt-dev-20` 为合法 28/28；`dgt-dev-03` 为异常 0/40。
- **跨运行证据**:R14 的随机空结果在 `dgt-dev-04`、`dgt-dev-12`，R15 转移到 `dgt-dev-03`，说明不是
  task/query 固定失败，而是刚建 namespace 的带过滤查询偶发返回空列表。
- **R16 预注册变量**:original/rewritten Pinecone query 对空列表做有限指数退避；耗尽抛错并由 `finally`
  清理，不写可用于 generation 的 trace。异常重试、query/filter/top-k/ranking 均不变。
- **外部使用**:1 次 Pinecone Development trace，临时向量已清理；0 次模型请求、0 model token、
  0 Validation/holdout case。
