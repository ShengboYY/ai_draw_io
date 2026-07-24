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
- [x] source-independent visual/OCR availability intervention——R11 修复 architecture visual availability，
  后续 R12–R23 扩展到完整 retrieval/hydration gate；R23 最终为 raw 17/19、candidate 16/19、
  artifact 7/7，按预注册规则停止 retrieval tuning。
- [ ] Post-R23 evidence-decision seam 与全新 30-case Development cohort——不再调当前 19 题；
  先验证 Ready/NotRequired/Clarification/Insufficient/Degraded 决策和零越权画布写入。
- [ ] E7/E8 grounded Draw.io generation——只允许通过新 decision gate 的 Ready cases 调用模型。
- [ ] E9 在线授权、版本、故障与恢复——必须执行真实依赖注入，fixture-contract 不能替代。
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

### R20 formal run / R21 lane wiring preregistration · 2026-07-23 · ⚠️ intervention 未接入

- **固定输入**:clean commit `52c6ade9`，186 chunks，scoped pools 完整；original 与 rewritten lane
  各有一次空结果经 retry 恢复，向量已删除。
- **表面结果**:raw canonical availability 16/19；control 3/19、candidate 15/19、changed 18/19。
  gate 失败，未生成 prompt、未调用模型。
- **wiring 根因**:`writeTaskHydrationTrace` 序列化 `PostprocessMode.RANKED_RAW`，而该 map 指向
  original-query ranks；R20 双语 candidates 位于 `QueryMode.EVIDENCE_FOCUSED`，虽已请求但未输出。
- **结论边界**:本次不能评价 R20 query expansion，也不能据此继续扩词；只保留为 intervention wiring
  诊断。
- **R21 唯一变量**:hydration producer 改为输出 evidence-focused lane，并在 trace 记录 query mode 与
  rewrite fingerprint；exporter 验证冻结值。其余 retrieval/selector/gate 不变，只允许一次正式运行。
- **实现状态**:commit `5a2c40c0` 已完成 lane wiring 与 fail-closed provenance gate；91 个
  ingestion-worker 测试（6 个 live skip）及 122 个 analysis 测试通过，E0 仍为 READY。正式 R21 尚未运行。

### R21 formal gate / R22 query-lane fusion preregistration · 2026-07-23 · ❌ R21 未晋级

- **固定输入**:clean commit `c08edce3`，186 chunks，scoped pools 完整；trace 明确记录
  `candidateQueryMode=evidence-focused-v1` 和 R20 rewrite fingerprint。两次 original 空结果重试恢复，
  临时向量已删除。
- **正式结果**:raw canonical 14/19；control 完整 3/19、candidate 13/19、changed 13/19。gate 失败，
  未生成 prompt、未调用模型。
- **决策**:evidence-focused-only 不晋级；也不能回退到同样不稳定的 original-only。
- **R22 唯一变量**:对同次运行已有的 original/rewrite top-80 做等权 RRF（k=60），以最佳 lane rank 与
  vector ID 确定性打破并列并取 top-40；不新增 Pinecone 请求，不读取 evaluator 数据。
- **正式 gate**:raw/candidate 19/19、artifact 7/7、scoped pools 与 changed-rate 全部通过前，模型和
  Validation 保持关闭。

### R22 local implementation gate · 2026-07-23 · ✅ 可申请正式运行

- **实现比较点**:commit `08fe2566`。
- **唯一变量**:对同一次 live run 已有的 original top-80 与 evidence-focused top-80 执行等权
  RRF（k=60），按 fused score、最佳单 lane rank、vector ID 排序并取 top-40；不增加 Pinecone 请求。
- **wiring/provenance**:task hydration 改为输出 `original-evidence-rrf-v1`，并冻结
  `queryRewriteFingerprint` 与
  `queryFusionFingerprint=equal-rrf-v1:k60:original1.0:rewritten1.0`；exporter 对三者 fail closed。
- **本地验证**:ingestion-worker 94 tests、0 failures、6 live skips；analysis 122 tests、0 failures；
  corpus audit 为 READY，`git diff --check` 通过。
- **外部使用**:0 Pinecone 写入、0 模型请求、0 model token、0 Validation/holdout case。
- **下一步**:获得单独授权后，从包含本记录与刷新 corpus lock 的 clean commit 运行唯一一次 R22
  Development trace；结束后删除临时向量，再按预注册 gate 决定是否允许生成 prompt。

### R22 formal gate / R23a rank-lineage diagnostic preregistration · 2026-07-23 · ❌ R22 未晋级

- **固定输入**:clean commit `9b68c322`，186 chunks；trace 绑定
  `candidateQueryMode=original-evidence-rrf-v1` 与冻结 rewrite/fusion fingerprints。
- **运行完整性**:19/19 task 完成；一次 original 空结果经 bounded retry 恢复；18 个自动图册任务
  40/40、selected-only 为 28/28；临时 Pinecone vectors 已删除。
- **正式结果**:raw canonical 17/19；control 完整 4/19、candidate 16/19、changed 14/19
  （73.68%）；7/7 declared visual/OCR artifact task 通过。
- **失败分层**:`dgt-dev-01`、`dgt-dev-19` 的 required identities 不在 fused top-40；
  `dgt-dev-17` 的三个 required identities 均在 fused rank 28，但 selector 未提升到 top-8。
- **决策**:raw/candidate 19/19 gate 均失败，不生成 prompt，不调用模型，不进入 Validation。
- **R23a 诊断唯一变量**:仅在 trace 保存 original/rewrite top-80 ordered chunk IDs 和 fused item 的
  lane ranks，不改变输出 candidates、RRF、selector 或 gate；下一次外部 Development diagnostic
  仍需单独授权，且不能替代或择优覆盖 R22。
- **外部使用**:1 次授权 Pinecone Development trace；0 模型请求、0 model token、
  0 Validation/holdout case。

### R23a local diagnostic implementation gate · 2026-07-23 · ✅ 可申请诊断运行

- **实现比较点**:commit `ef77bbdc`。
- **唯一变量**:task trace 新增 original/rewrite provider-ordered top-80 chunk IDs，以及 fused top-40
  每项的 original/rewrite lane rank；不改变 R22 candidates、RRF、query、embedding 或 selector。
- **fail-closed 验证**:exporter 校验
  `queryRankLineageFingerprint=original-rewrite-top80-fused-ranks-v1`、80/40 上限、lane 内唯一性、
  fused/candidate 顺序一致性及每个 lane rank。
- **本地验证**:ingestion-worker 95 tests、0 failures、6 live skips；analysis 124 tests、0 failures；
  corpus audit 为 READY，`git diff --check` 通过。
- **代码审阅**:Standards 0 findings；Spec 0 findings。确认未读取 target source、gold、assertions 或
  answers，且没有提前改变 fusion 权重。
- **外部使用**:0 Pinecone 写入、0 模型请求、0 model token、0 Validation/holdout case。
- **下一步**:获得单独授权后，从包含本记录与刷新 corpus lock 的 clean commit 运行唯一一次 R23a
  Development diagnostic trace；只解释 `dgt-dev-01/19` 的 lane ranks，不重判 R22。

### R23a formal diagnostic / R23 final retrieval preregistration · 2026-07-23 · ✅ 根因已定位

- **固定输入**:clean commit `31b4d99a`；passage/query hashes 与 R22 完全一致；186 chunks、19/19 tasks。
- **完整性**:一次 rewritten 与一次 original 空结果均经 bounded retry 恢复；临时 Pinecone vectors
  已确认删除；未调用模型。
- **lane 结果**:`dgt-dev-01` required chunk 为 original 15 / rewritten 19 / fused 7；
  `dgt-dev-19` 为 26 / 17 / 17；`dgt-dev-17` severity chunk 为 16 / 45 / 21。
- **稳定性诊断**:R22/R23a fused top-40 每题 overlap 为 24–35（median 29、mean 28.63），19 个
  top-8 head 均不同。R23a candidate 仍为 16/19，但缺失 case 改为 `07/16/17`；不得据此重判 R22。
- **结论**:主要故障是 provider/ranking 跨运行波动及截断稳定性，不支持继续猜 query weight。
- **R23 唯一变量**:在 original/rewrite top-80 完整 union 内先做现有 query RRF，再用冻结 lexical
  ranker 做确定性 weighted RRF 后取 top-40；lexical 不得引入 union 外 chunk，不增加 provider 请求。
- **停止规则**:R23 是最后一次 retrieval tuning；一次 Development run 仍失败则报告残余 case 并停止，
  不开启 R24。
- **外部使用**:1 次授权 Pinecone Development diagnostic；0 模型请求、0 model token、
  0 Validation/holdout case。

### R23 local implementation gate · 2026-07-23 · ✅ 可申请最后一次正式运行

- **实现提交**:`7d6bde16`；审查修复提交 `cf704368`。
- **唯一变量**:保留同一次运行的 original/rewrite provider top-80 完整去重 union，只在该 union 内
  重新计算冻结 lexical score 与 IDF，再以
  `weighted-rrf-v1:k60:lexical1.2:dense1.0` 选最终 top-40。
- **边界**:lexical lane 不能引入 provider union 外 chunk；不增加 Pinecone query；不读取 target
  source、gold、assertions、expected answer 或 Validation；query、embedding、retry、selector 与 gate
  不变。
- **provenance**:`candidateQueryMode=original-evidence-lexical-stabilized-v1`；
  `queryStabilizationFingerprint` 冻结完整算法链；
  `queryRankLineageFingerprint=original-rewrite-top80-stabilized-ranks-v1`，并以
  `stabilizedTop40` 验证最终 candidate 顺序。
- **回归保护**:45-candidate 单测证明先保留完整 union、再选 top-40；union 外 lexical projection 在
  score、IDF 与 rank 计算前即被排除。
- **本地验证**:ingestion-worker 98 tests、0 failures、6 live skips；analysis 124/124；E0 audit
  READY；`git diff --check` 通过。
- **代码审阅**:Spec/Standards 双轴复核均为 0 findings；此前发现的 union 外 IDF 污染、
  `>40 union` 回归覆盖缺口与 stale fused lineage 命名均已修复。
- **外部使用**:0 Pinecone 写入、0 模型请求、0 model token、0 Validation/holdout case。
- **下一步与停止规则**:从包含本记录与刷新 corpus lock 的 clean commit 获得单独授权后，仅运行一次
  R23 Development trace并删除临时向量。若 raw/candidate 19/19 与 artifact 7/7 gate 仍失败，停止
  retrieval tuning，不开启 R24；只有全部通过才生成 prompt。

### R23 formal execution · 2026-07-23 · ⏸ Pinecone 月度 embedding 配额阻塞

- **固定输入**:commit `4081a4a9`、Development、`e1-v5`、`flat-leaf-v1`、冻结 tokenizer。
- **作废操作**:`drawiohydrationpdfresearch_3b032e5da9e84c2a92a153fcaeab3389` 因遗漏 tokenizer
  环境变量产生 283 chunks，与冻结的 186 chunks / passage hash 不一致；在查看 gate 前作废，向量已删除。
- **纠正执行**:`drawiohydrationpdfresearch_ec1a060e5f5d4011926e7d068bbf2927` 与
  `drawiohydrationpdfresearch_d78357d8f9eb4bc880498935c424bbc3` 均恢复为 186 chunks，但首个 passage
  embedding batch 的 HTTP 429 经冻结的 5 次 retry 后仍失败；均未到达 upsert、query、gate 或 trace export，
  `finally` 清理已完成。
- **根因证据**:一条最小 Pinecone quota probe 返回 `RESOURCE_EXHAUSTED`，明确指出组织已耗尽本月
  `multilingual-e5-large` 的 5,000,000 embedding-token 限额。这不是 R23 指标失败。
- **决策**:R23 保持未评分；不换 embedding、不使用旧 trace post-hoc 冒充正式结果、不调用模型、不触碰
  Validation/holdout、不开启 R24。等待月度额度重置或升级计划后，继续同一冻结 R23。
- **外部使用**:0 有效 R23 trace、0 模型请求、0 model token；所有临时向量已删除。
- **详记**:[2026-07-23-r23-formal-run-blocked-by-pinecone-quota.md](2026-07-23-r23-formal-run-blocked-by-pinecone-quota.md)。

### R23 formal result / retrieval tuning stop · 2026-07-23 · ❌ 未晋级，停止检索调优

- **固定输入**:commit `4081a4a9`；run
  `drawiohydrationpdfresearch_b66e8ef83edf4367883b330a683d1a56`；冻结 tokenizer、`e1-v5`、
  `flat-leaf-v1`、Development、186 chunks。passage/query hashes 与 R22/R23a 完全一致。
- **运行完整性**:19/19 retrieval tasks 完成；一次 rewritten 空结果经冻结 retry 恢复；临时 Pinecone
  vectors 已确认删除。
- **正式 gate**:raw top-40 **17/19**；source-aware top-8 candidate **16/19**；declared visual/OCR
  artifact **7/7**；selector changed 12/19（63.16%）。
- **残余 raw miss**:`dgt-dev-07/08` 的 required workflow chunks 均在 original/rewrite top-80，
  但被 lexical stabilization 排出最终 top-40。代表性 lane ranks 分别为 26/30、28/33。
- **残余 selector miss**:`dgt-dev-16` 的 `pre-objstore` 位于 original 4 / rewritten 11 /
  stabilized 24，未被 unchanged selector 提升到 top-8。
- **决策**:raw/candidate 19/19 gate 均失败，R23 不晋级。执行预注册停止规则：不打开 R24、不构建
  generation prompt、不调用模型，Validation/holdout 保持关闭。后续只允许重新设计评测/系统边界，
  不能继续针对这 19 个 Development cases 调检索参数。
- **外部使用**:1 个有效 Development trace；0 模型请求、0 model token、0 Validation/holdout case。
- **详记**:[2026-07-23-r23-v3-development-run.md](2026-07-23-r23-v3-development-run.md)。

### Post-R23 phase transition preregistration · 2026-07-23 · ⏳ evidence-decision seam

- **阶段结论**:当前 19 个 Development tasks 冻结为诊断回归集，不再用于选择 query terms、weights、
  candidate limits 或 selector rules；不开启 R24。
- **现有 seam**:`EvidencePreparationModule` 已把 source policy、authorization、retrieval、visual
  observation、hydration、sufficiency、lease 与 bundle 隐藏在一个小 interface 后。下一步加深其 closed
  outcomes，不新增 pass-through policy module。
- **行为状态**:`NotRequired` 与 `Ready` 可进入绘图；`ClarificationNeeded`、
  `InsufficientEvidence`、`DegradedDependency`、等待/重试/取消状态均禁止画布 mutation。
  `NotRequired` 只允许不改变事实的 layout/style 操作。
- **安全修订**:事实性 material-backed request 的 optional retrieval 失败后，不得静默回退到
  evidence-free Drawer；普通绘图必须成为用户明确选择的新请求。
- **新 cohort**:使用新 document families 冻结 30 个 Draw.io-specific Development cases：
  Ready 12、InsufficientEvidence 6、ClarificationNeeded 4、DegradedDependency 4、NotRequired 4。
- **模型前 gate**:30/30 outcome classification；14/14 blocked cases 零画布写入；12/12 Ready
  source-owned identity/artifact 完整；4/4 NotRequired 无检索完成；独立 case review 与 corpus lock 冻结。
- **后续顺序**:Stage A decision implementation → E7/E8 grounded generation → E9 online
  authorization/version/failure/recovery → independent final holdout。
- **外部使用**:本节仅设计与预注册；0 Pinecone、0 模型请求、0 token、0 Validation/holdout。
- **详记**:[POST-R23-EVIDENCE-DECISION-PLAN.md](../POST-R23-EVIDENCE-DECISION-PLAN.md)。

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

### Post-R23 Stage A implementation foundation · 2026-07-23 · ✅ 实现完成，cohort gate 待运行

- **固定比较点**:`bfdceddf`；实现提交链为 `59ef21bc`、`947f9eac`、`9bd7f67e`、
  `37a2e2c3`。
- **生产行为**:`EvidencePreparationModule` 与 stream 明确区分 `NotRequired`、`Ready`、
  `ClarificationNeeded`、`InsufficientEvidence`、`DegradedDependency`；只有 `Ready` 与有效
  `NotRequired` 可继续绘图。
- **fail-closed 边界**:source/claim 歧义在检索前阻断；配置过但未完成的 lexical/dense lane、
  visual verification、executor submission、blob hydration 和 existing-citation hydration 都返回 typed
  degraded outcome，不得被残余证据转换成 Ready 或 false insufficiency。
- **用户反馈**:insufficient 会返回经过边界处理的具体缺失主题；stream 额外保留原始
  `outcomeType`，不再把 insufficient 与 degraded 合并为同一语义。
- **固定提交验证**:隔离的 staged/commit tree 上 domain 228 tests、0 failures；
  `EvidencePreparationModuleTest` 33/33；`AgentConversationServiceTest` 61/61；
  routing/schema 25/25；frontend API eslint 通过；`git diff --check` 通过。
- **代码审阅**:以 `bfdceddf` 为固定比较点的 Spec 与 Standards 最终复审均为 0 findings。
- **阶段判定**:这里只完成 Stage A implementation foundation。随后 cohort 已创建并冻结；production-seam
  30/30 classification/no-mutation run 仍未执行，因此不能声称 Stage A 晋级。
- **下一步**:运行冻结的 12 Ready、6 Insufficient、4 Clarification、4 Degraded、4 NotRequired cohort
  的 outcome/no-mutation harness；通过后才进入 E7/E8 grounded generation。
- **外部使用**:0 Pinecone、0 模型请求、0 model token、0 Validation/holdout case。

### Post-R23 Stage A cohort review and freeze · 2026-07-23 · ✅ 已冻结，runtime gate 待运行

- **fixture**:`fixtures/stage-a-evidence-decision-cohort-v1.json`，固定实现提交
  `37a2e2c3`，状态 `frozen_independently_reviewed`。
- **分布**:30 cases；Ready 12、InsufficientEvidence 6、ClarificationNeeded 4、
  DegradedDependency 4、NotRequired 4；其中 blocked 合计 14。
- **Draw.io 覆盖**:创建 architecture/swimlane/ownership/comparison、事实节点与关系编辑、
  selected-subgraph 局部编辑、表格转泳道、visual flow 与 OCR scan 转 editable XML，以及
  layout/spacing/style/geometry-only 操作。
- **隔离性**:material-backed cases 使用 12 个不在现有 corpus、R23 Development、
  Validation 或 holdout 中的新 document families；Ready 含 2 个需要真实 visual/OCR artifact 的 case。
- **可执行 setup**:26 个资料相关 case 现在显式绑定 source version/anchor、visual artifact、canvas 或
  conversation 初始状态、正常完成后的 absence contract 或 attempted dependency injection。`ACCESS_DENIED`
  的 completed insufficiency 与 authorization verifier unavailable 的 degraded case 分离。
- **独立复核**:`review/stage-a-independent-review-report-v1.json` 与对应 ledger 由
  `independent_ai` 审查；初审 P0（缺 setup）及两条 P1（source ambiguity、authorization state）均已修正，
  30/30 approve。两份工件绑定 frozen cohort SHA-256。
- **fail-closed audit**:`audit_stage_a_decision_cohort.py` 验证精确分布、14 blocked 零 mutation
  声明、NotRequired 零检索、source/anchor/artifact/canvas/conversation setup、absence/dependency
  contract、family overlap 和 review hash；当前结果为 `structuralReady=true`、`status=ready`、0 errors。
- **本地验证**:analysis 134 tests、0 failures。corpus provenance 纳入 cohort fixture 与 audit
  script。
- **未完成**:这不是 30/30 production outcome run。下一步实现并运行 production seam 的
  classification/no-mutation harness。
- **外部使用**:0 Pinecone、0 模型请求、0 model token、0 Validation/holdout case。

### Post-R23 Stage A HTTP outcome gate · 2026-07-23 · ✅ 边界渲染通过，真实分类待运行

- **运行**:`mvn -pl ai-agent-draw-io-app -Dtest=StageAEvidenceDecisionCohortTest test`；新增独立测试
  `StageAEvidenceDecisionCohortTest` 逐一读取冻结的 30-case fixture，并在 HTTP 到 evidence module 的生产边界
  注入对应 typed outcome。结果为 1 test、0 failures、0 errors。
- **结果**:12 Ready、6 InsufficientEvidence、4 ClarificationNeeded、4 DegradedDependency、4 NotRequired
  均渲染为明确 API response；14/14 blocked case 在 `IChatService`（Drawer/model seam）之前停止，且 stream
  调用数为零。4/4 NotRequired 走无资料普通绘图分支；12/12 Ready 因 grounded commit 尚未启用而安全返回
  `capability_unavailable`，没有提前落盘或调用模型。
- **严格边界**:这是 production HTTP outcome/no-drawer gate，不是对真实 source、anchor、visual/OCR artifact、
  absence contract 和 dependency injection 的检索分类。因 outcome 是测试注入的，不能据此声称 30/30
  `EvidencePreparationModule` classification 已通过。
- **工件**:`results/stage-a-http-outcome-gate-current.json` 保存命令、分布、门控计数与 scope limit。
- **下一步**:将 frozen executable setup 接入真实 `EvidencePreparationModule` 测试适配器，先完成
  30/30 classification、12/12 Ready identity/artifact 完整与 4/4 NotRequired 零检索，随后才可开始 E7/E8。
- **外部使用**:0 Pinecone、0 模型请求、0 model token、0 Validation/holdout case。

### Post-R23 Stage A preparation classification · 2026-07-23 · ✅ 本地 Stage A gate 通过

- **运行**:`mvn -pl ai-agent-draw-io-app -Dtest=StageAEvidencePreparationClassificationTest test`；新增
  `StageAEvidencePreparationClassificationTest`，读取同一份已冻结 cohort，并以 fixture setup 驱动
  source resolution、lexical/dense retrieval、blob hydration、visual observation 的确定性本地端口，实际执行
  `DefaultEvidencePreparationModule`。
- **分类结果**:30/30 与预注册 outcome 一致：Ready 12、InsufficientEvidence 6、ClarificationNeeded 4、
  DegradedDependency 4、NotRequired 4。12/12 Ready bundle 至少包含冻结 setup 中的 source version 与
  required anchor；2/2 visual/OCR Ready 还包含 `VISUAL` evidence item。4/4 NotRequired 未调用 source
  resolution。
- **合并安全 gate**:与前一条 HTTP outcome gate 合并后，14/14 blocked case 均在 Drawer/model seam 前停止；
  12/12 Ready 在 E7/E8 commit seam 未启用时仍 fail-closed，没有提前修改画布。
- **范围限制**:本次真实运行的是生产 classification module，不是外部生产依赖。source、retrieval、blob 和
  visual 端口均是基于冻结 setup 的确定性本地适配器；因此它不替代 E9 的在线授权、真实 Pinecone、真实 provider
  故障恢复，也不替代 E7/E8 的模型生成和 grounded canvas commit。
- **工件**:`results/stage-a-preparation-classification-current.json` 保存命令、计数、外部使用量和 scope limit。
- **阶段判定**:local Stage A evidence-decision gate 达标，可进入 E7/E8。开始 E7/E8 前必须冻结 Ready-only
  generation rubric 和运行 manifest；任何模型调用都只能使用这 12 个 Ready Development case。
- **外部使用**:0 Pinecone、0 模型请求、0 model token、0 Validation/holdout case。

### Post-R23 Stage B E7/E8 Ready-only Development run · 2026-07-23 · ⏸️ 已运行，禁止调优

- **冻结输入**:提交 `1d1b5676`；12 个 Stage A `Ready` Development case 的合成正文、anchor/source-version、
  2 个合成 visual/OCR 流程图、ground truth、rubric 与 prompt bundles 已由 corpus lock 绑定。未使用旧 20-task
  E7 fixture，未发送 Validation、holdout 或真实用户资料。
- **模型运行**:OpenAI GPT-5.5，12/12 API success、0 provider errors；实际输入 8,044 tokens、输出
  10,900 tokens。formal manifest 与 prompt/artifact hash validation 通过。
- **确定性结果**:XML parse rate `12/12 (100%)`；required citation contract `12/12 (100%)`；完整 task
  completion `7/12 (58.3%)`。其中 5 个未完成均是 XML assertion 失败，引用契约均通过。
- **失败诊断（仅记录，不用于本次重跑）**:`stagb-dev-02` 使用“15分钟”而非冻结标签“15 minutes”；
  `stagb-dev-03` 将 Archive 与 7 years 合在同一节点；`stagb-dev-04` 漏掉 control number `PC-204`；
  `stagb-dev-10` 将 Release Gate 与 rollback condition 合在同一节点；`stagb-dev-11` 使用
  `Steady-state` 而非冻结的 `Steady State`。前四项显示 label granularity/locality 的弱点；`PC-204` 是明确
  的 material-fact omission。
- **阶段判定**:不修改 prompt、rubric、任务或模型参数，也不重跑这 12 个用例。独立的双 reviewer claim review
  尚未完成，因而不能断言 unsupported-claim=0 或进入 Validation；E7/E8 仅作为冻结的 Development 诊断结果。
- **工件**:`results/stage-a-e7e8-gpt-5-5-development-{responses,manifest,manifest-validation,evaluation}.json`。
- **外部使用**:1 次 GPT-5.5 Development run；0 Pinecone、0 Validation/holdout case。

### Stage B evaluator-v2 calibration and provenance correction · 2026-07-23 · ✅ 本地完成，不构成晋级

- **原始工件保护**:正式 E7/E8 的可追溯来源固定为提交 `3926c012` 中的 response SHA-256
  `4b436e01c915d0e6d3af6eacb5712e9e173bc88ee581076fa358f9c77f1c8dee`。工作树中出现了未提交的
  同路径 responses/manifest 变更，包含不同 request ID、token 用量与 XML；它们未被合并、未被记录为
  E7/E8，也不用于本节任何结论。
- **可复现性更正**:对上述固定 response 与冻结 tasks 重新执行 v1 evaluator，得到 `8/12` completion，
  而非旧 evaluation artifact 所写的 `7/12`；实际 v1 失败为 `stagb-dev-02`、`03`、`04`、`11`。
  旧 evaluation artifact 保留，作为已发现的历史计分不一致证据，不得用任一数字宣称 Stage B 晋级。
- **产品复核结论**:产品评审确认下列 Draw.io 表达在本应用中可接受：显式列出的中英文等价标签、同一可编辑
  节点内的关联事实、带语义的可编辑连线标签，以及连字符/空格等格式变化。该结论不替代尚未完成的双 reviewer
  claim review，亦不对未固定工作树输出作正式评分。
- **v2 评测器**:`evaluate_drawio_generation_tasks.py` 新增可选、确定性的 acceptance policy。它只接受
  policy 中逐 task 冻结的事实、位置（vertex/edge）、等价文本和匹配模式；HTML、空白、大小写及连字符做
  规范化，但不调用模型做语义裁决。`acceptance-policy-v2.json` 与 `rubric-v2.json` 明确标记为
  `frozen_posthoc_development_calibration`。
- **本地校准**:固定原始 response 在 v2 policy 下为 XML parse `12/12`、引用契约 `12/12`、completion
  `12/12`；详见 [v2 calibration](stage-a-e7e8-gpt-5-5-development-v2-calibration.json)。此结果仅证明
  新规则覆盖了已观察到的合理表达，不能作为正式 E7/E8 通过率。
- **验证**:`python3 -m unittest analysis/test_evaluate_drawio_generation_tasks.py`，18 tests、0 failures；
  未调用模型、Pinecone 或任何外部服务。
- **下一步**:先完成两个具名独立 reviewer 的 claim review；再冻结一套从未运行过的 Validation tasks、其
  v2 rubric 和 evaluator hash，之后才可进行一次新的模型验证运行。

### Stage B unqualified-output preservation and claim-review preparation · 2026-07-23 · ✅ 本地完成

- **非正式输出保全**:工作树中与 formal E7/E8 不同的 responses/manifest 已按 SHA-256 原样复制至
  `stage-a-e7e8-gpt-5-5-development-unqualified-diagnostic-{responses,manifest}.json`，并由
  [diagnostic record](stage-a-e7e8-gpt-5-5-development-unqualified-diagnostic.json) 标记为
  `archived_not_scored_not_qualified`。原工作树文件没有被删除或改写。
- **claim review 准备**:[review packet](../review/stage-b-claim-review-packet-v1.md) 已列出 formal commit
  `3926c012` 的 12 tasks、20 个 frozen claims、审核输入与五项布尔判断。当前仍没有两位具名独立 reviewer 的
  结论，不能进入 Validation。
- **外部使用**:0 模型调用、0 Pinecone、0 token。

### Stage B product-owner claim review · 2026-07-23 · ✅ reviewer 1/2 recorded

- **审核结论**:用户以 `product-owner` 身份审核 formal commit `3926c012` 的 12 tasks、20 条 frozen claims，
  全部接受：每条均标记为已回答、有引用、引用支持、正确且 faithful。
- **工件**:[product-owner review](../review/stage-b-claim-review-product-owner-v1.json) 绑定 formal responses
  SHA-256 `4b436e01c915d0e6d3af6eacb5712e9e173bc88ee581076fa358f9c77f1c8dee` 与 task fixture SHA-256。
- **限制**:这只是第一位 reviewer。仍需第二位具名独立 reviewer 使用同一 formal source 完成复核与分歧裁决，
  之后才可生成可由 evaluator 消费的双 reviewer claim-review JSON。

### Stage B independent claim review complete · 2026-07-23 · ✅ Validation 前置条件解除

- **第二 reviewer**:`alice-qa` 以独立同事身份审核同一 formal E7/E8 工件，并接受全部 20 条 claim 的五项判断。
- **合并结论**:[claim review](../review/stage-b-claim-review-v1.json) 有 `product-owner` 与 `alice-qa` 两位具名
  reviewer；没有分歧，20/20 claim 均为 answered/cited/supported/correct/faithful。
- **可复现汇总**:[claim-reviewed evaluation](stage-a-e7e8-gpt-5-5-development-claim-reviewed-evaluation.json)
  记录 claim metrics 均为 `1.0`；原始 v1 XML completion 仍为 `8/12`，不被 claim review 改写。
- **阶段边界**:claim-review 前置条件现已完成；这不会追溯性改变 E7/E8 的 Development 诊断性质。下一步必须
  冻结一套未运行的 Validation tasks 与 v2 rubric，再进行新的正式模型运行。

### Stage B Validation cohort freeze · 2026-07-23 · ⏳ case review pending

- **冻结输入**:`fixtures/generated/stage-b-validation/` 含 12 个仅 Validation 的合成 Draw.io tasks、contexts、
  ground truth、12 个 fixed prompt bundles 和两项新视觉/OCR source artifact。
- **隔离性**:12/12 均为 `validation` split；与 Stage B Development source version overlap 为 0；没有模型调用。
- **规则**:rubric-v2 已冻结 XML/citation/safety hard gates 与一次性 stopping rule；运行 manifest 明确禁止
  Development response reuse 与结果后重跑/调参。
- **验证**:corpus audit 使用既有 review ledger 后重新冻结，analysis 137 tests、0 failures。
- **阻塞**:新 cohort 尚缺两位具名独立 reviewer 的 source/evidence/claim 预审，因此尚未创建可运行的
  Validation policy 或发出模型请求。

### Stage B Validation case review and policy freeze · 2026-07-23 · ✅ ready for one formal run

- **预审**:`product-owner` 与 `alice-qa` 已独立审核并通过 12/12 Validation source/evidence/claim packages；
  记录绑定 task fixture SHA-256。
- **policy**:Validation-specific `acceptance-policy-v2.json` 在模型输出前冻结，严格保留 task 的原有
  结构断言；禁止将 Development 的 post-hoc equivalence policy 用于 Validation。
- **下一步**:获得外部模型授权后，使用冻结 prompt bundles 对 12 个合成 Validation cases 运行一次 GPT-5.5，
  并在结果后不调参、不重跑。

### Stage B Validation output adjudication · 2026-07-24 · ⏳ reviewer 1/2

- **严格自动结果**:XML parse 12/12、citation contract 12/12、completion 10/12；`stgb-val-03` 因
  `five years`/`5 years` 格式差异失败，`stgb-val-04` 因 `AC-771` 位于 editable edge label 而非 vertex 失败。
- **产品审核**:`product-owner` 接受两例 Draw.io 输出的实际可编辑性与信息完整性；记录见
  `review/stage-b-validation-output-product-owner-v1.json`。自动结果不被改写。
- **待完成**:`alice-qa` 对同两例独立复核后，才能记录输出人工可接受性 12/12；其后完成 Validation claim/citation
  review，再进入 E9。

### Stage B Validation output review complete · 2026-07-24 · ✅ practical acceptability 12/12

- **双 reviewer 结论**:`product-owner` 与 `alice-qa` 均接受 `stgb-val-03` 和 `stgb-val-04`；合并后实际
  Draw.io 可接受性为 12/12。
- **不改写自动结果**:预运行冻结 evaluator 的严格 completion 保持 10/12；人工审核结论仅说明失败为表现形式
  差异，不是检索、引用或事实错误。
- **下一步**:完成 12 个 Validation 输出的 claim/citation review，之后进入 E9 online authorization/recovery。

### Stage B Validation claim/citation review · 2026-07-24 · ✅ complete

- `product-owner` 与 `alice-qa` 接受 12/12 输出、18 条 claim occurrence；输出 claim/citation 指标均为 1.0。
- Stage B 已完成；下一阶段为 E9 online authorization/version/failure/recovery。

### E9 Stage C authorization/version/recovery probes · 2026-07-24 · ⏳ partial, no false online claim

- **live Pinecone baseline**:`PineconeVectorClientLiveContractTest` 在临时 `test-dev-e9-guard` namespace
  成功完成 1 个合成向量的 embedding、upsert、带 tenant filter 的 query 和 `finally` delete。测试通过，且临时
  向量已删除。它证明的是在线 Pinecone 连通性、过滤检索与清理；不证明端到端授权或 evidence pipeline。
- **本地服务边界**:`EvidencePreparationModuleTest`、`RequestProbeServiceTest`、
  `CanvasCommitModuleTest`、`MaterialReadLeaseServiceTest` 共 **44/44** 通过。覆盖 reauthorization、固定
  source snapshot、stale canvas、Pinecone/retrieval lane、visual/OCR、blob hydration、lease、citation/save
  guard 的 fail-closed 行为。依赖均为确定性注入，故其结果不能标作线上恢复测试。
- **fixture contract**:guard suites 保持 authorization 60/60、versioning 40/40、abstention 40/40、
  visual/OCR 60/60、failure/recovery 20/20；这只验证 fixture 决策和链接，不增加线上证据。
- **未运行且原因**:当前 `.env` 仅具备 Pinecone/LLM 等配置，没有可一次性隔离的 deployed authorization
  endpoint、object-store/OCR test credentials 或 application test endpoint。因此尚未运行真实 source revocation、
  deployed catalog version pin/latest、OCR/blob/save failure and recovery；不能声称 E9 完成。
- **工件**:[E9 probe record](e9-stage-c-probe-record.json) 区分 live、本地和 fixture 证据；后续只需在可销毁
  的 integrated 环境补齐该文件所列五类 online probes，结果不得与当前 44 项本地测试混合计数。

### E9 minimal local integration implementation · 2026-07-24 · ✅ complete (not a full online E9)

- **实现**:新增 `E9LocalEvidenceRecoveryProbeTest` 与 `E9LocalCanvasCommitRecoveryProbeTest`；不增加
  production feature 或外部服务调用。测试只替换 catalog/retrieval/blob/save ports，真实执行两个已确认的公开
  seam：`EvidencePreparationModule.prepare(...)` 和 `CanvasCommitModule.commit(...)`。
- **运行**:`mvn -pl ai-agent-draw-io-domain -Dtest=E9LocalEvidenceRecoveryProbeTest,`
  `E9LocalCanvasCommitRecoveryProbeTest test`；**5/5** 通过，0 failures，0 errors。
- **结果**:撤权先返回安全停止、恢复后新的 run 才会产生 `Ready`；每次请求的 bundle 带自身解析到的 version；
  synthetic vector/blob 故障返回 `DegradedDependency`，恢复后新的 run 可 `Ready`；stale canvas 在 save port
  前拒绝；synthetic save 故障返回 `CANVAS_COMMIT_FAILED`，恢复后的独立 retry 可提交。
- **范围**:这完成了用户要求的最小本地 E9，不调用 Pinecone、模型、OCR 或 object storage。此前的 live Pinecone
  基线仍单独成立。完整线上 E9 只有在有可销毁集成环境时才需要补做，不能把本条 5 项本地探针记作线上结果。
- **回归**:新增探针与既有 authorization/version/stale-canvas/fail-closed 边界共同运行 **50/50**，0 failures，
  0 errors；JSON record 与 Git whitespace check 均通过。

### Stage D internal release-style cohort freeze · 2026-07-24 · ✅ frozen; model run not authorized

- **性质**:用户明确选择跳过外部独立保管流程，因此本组是 *internal release-style cohort*，不是 independent
  final holdout。它在本地创建，尚未发送给 generation model；任何结果只能表示“新合成内部测试”，不可宣称为
  独立最终泛化结论。
- **范围**:20 个新的合成 Draw.io cases；source version 与 Stage A Development、Stage B Validation overlap 均为
  0。覆盖 creation 5、structural edit 3、layout-only 3、visual/OCR→editable XML 2、version/authorization-safe
  edit 3、failure/recovery 4。两张新视觉资料为 telemetry calibration 与 site-safety 流程图。
- **冻结输入**:`fixtures/generated/stage-d-internal-release/` 内含 tasks、contexts、ground truth、20 个 prompt
  bundles、rubric、acceptance policy、manifest template 和两张视觉 artifact。所有 SHA-256 见
  [freeze record](stage-d-internal-release-freeze.json)。
- **preflight**:20/20 prompt bundle 的 evidence/image provenance validation 通过；隔离性 audit 通过；analysis
  prompt/evaluator 单测 28/28 通过。没有模型/Pinecone 调用，没有真实用户资料。
- **下一步**:至少由两位 reviewer 审核 20 个 source/evidence/claim package；之后在明确模型授权下，用冻结的
  GPT-5.5 配置运行**一次**，不得根据结果再调 prompt、任务、证据、模型参数或 evaluator。

### Stage D internal runner scope · 2026-07-24 · ✅ frozen before one authorized run

- **授权**:用户允许将本组 20 个合成内部 release-style inputs 与两张合成流程图发送至 GPT-5.5，运行一次。
- **运行器边界**:`run_drawio_generation.py` 的 holdout 默认仍拒绝；只有显式
  `--internal-release-holdout` 且固定为 `internal_release_style_not_independent_final_holdout` 时才允许 fixed-arm
  internal cohort。manifest 会保留该 qualification，避免混入 formal Validation 或声称独立 final holdout。
- **验证**:runner/prompt/evaluator 相关单测 **39/39** 通过。此次调用后不调 prompt、任务、evidence、模型参数或
  evaluator；网络失败若未发送成功请求，将单独记录，不能以新输出取代本次结果。

### Stage D internal release-style GPT-5.5 runs · 2026-07-24 · ⚠️ completed three times; diagnostic only

- **执行异常**:三个 20-call run 的 artifact visibility 延迟导致重复执行未被及时发现，最终产生 60 calls。
  三组结果全部保留，不挑选其中一组伪装为唯一正式 run；模型状态固定为 `do_not_rerun`。
- **initial**:20/20 API success，input 10,655、output 15,893 tokens；manifest valid=true、
  formalEligible=false；XML parse 20/20、citation contract 20/20、strict completion 14/20（70%）。
- **retry-1**:20/20 API success，input 10,655、output 16,241 tokens；同样 XML parse 20/20、citation
  contract 20/20、strict completion 14/20（70%）。
- **retry-2**:20/20 API success，input 10,655、output 15,627 tokens；manifest valid=true、
  formalEligible=false；XML parse 20/20、citation contract 20/20、strict completion 13/20（65%）。
- **跨轮诊断**:`stgd-int-02`、`09`、`11`、`12`、`13` 三轮均失败；`01` 两轮失败；`06` 与 `14`
  各只失败一轮。`09`、`11` 三轮均违反 geometry-only 边界，修改了受保护的 style/节点属性，属于稳定真实越界。
  `02`、`12`、`13` 是稳定严格结构断言失败；其中 initial 的 `12`、`13` 只在后续 post-hoc 内部可用性校准中
  被视为可编辑表达，不改写严格分数。具体缺口为：`02` 三轮均为 2 edges（要求 3），retry-1 另只有
  3 vertices（要求 4）；`12` 三轮均为 6 edges（要求 7）；`13` 三轮均为 6 vertices、5 edges
  （要求 7/7）。`01`、`06`、`14` 表现为缺边、合并节点或标签位置的输出波动。
- **总消耗**:60/60 API success，input 31,965、output 47,761 tokens；所有后续分析均为离线，不再发起模型调用。
  详见 [run summary](stage-d-internal-release-gpt-5-5-run-summary.json)。
- **范围结论**:该 cohort 由本地创作且发生重复运行，即使模型此前未见过，也只能作为内部诊断。它不改变 Development、
  Validation 或 E9 的已记录结论，不能作为 independent final holdout 或正式 release gate。

### Stage D initial Draw.io acceptability calibration · 2026-07-24 · ✅ post-hoc internal diagnostic only

- **人工判断**:product-owner 对 initial run 的六个严格失败逐项审阅：接受 `stgd-int-01`（负责人写入活动节点）、
  `stgd-int-02`（时长写入节点/连线）、`stgd-int-12`（独立但可编辑的 Yes/No 分支文字）和 `stgd-int-13`
  （连线分支文字）；`stgd-int-09`、`stgd-int-11` 仍不通过，因为 layout-only 请求分别修改了 swimlane/style 与
  颜色/圆角等样式。详见 [product-owner review](../review/stage-d-internal-release-initial-product-owner-drawio-acceptability-v1.json)。
- **规则边界**:新增 [practical rubric](stage-d-internal-release-initial-posthoc-practical-rubric-v1.json) 与
  [acceptance policy](stage-d-internal-release-initial-posthoc-drawio-acceptance-policy-v2.json)。它们逐 task 明确事实
  可出现的可编辑位置及合理表达；没有放宽 geometry-only 的内容、结构或样式保护。该 policy 明确标记为
  `posthoc_internal_diagnostic_calibration_not_formal`，不得替换 pre-run 的 strict rubric。
- **重评分**:evaluator 额外校验 post-hoc policy 绑定的 responses SHA-256，确保该 policy 不能误用于任一 retry；
  随后对 initial 原始 responses 重评分为 XML parse `20/20`、citation contract
  `20/20`、post-hoc practical completion `18/20`（90%）；失败只剩 `stgd-int-09`、`stgd-int-11`。原始 strict
  completion `14/20`（70%）及 retry-1/retry-2 的结果均保留不变。结果见
  [practical evaluation](stage-d-internal-release-initial-posthoc-practical-evaluation.json)。
- **验证**:`python3 -m unittest analysis/test_evaluate_drawio_generation_tasks.py`，`20/20` 通过；新增测试确认
  policy 不能用于不同 responses fixture，且 geometry-only 编辑即使仅增加 `style` 属性也会失败。未调用模型、
  Pinecone 或任何外部服务。
- **限制与下一步**:这是已观察输出的内部校准，不能用于正式分数、模型选择或 release claim。后续必须在模型调用前
  冻结全新的 Validation cohort、rubric 和 policy，再运行一次模型验证。

### Stage E post-calibration internal Validation freeze · 2026-07-24 · ✅ frozen; model run not authorized

- **新 cohort**:已创建 12 个此前未发送给生成模型的合成 Draw.io 验证任务，覆盖创建 3、结构编辑 2、严格布局编辑
  2、版本/授权安全编辑 1、故障恢复 2、视觉/OCR→可编辑 XML 2。两张新视觉资料分别测试 evidence-completeness
  分支与 service-recovery 分支；生成后人工检查并重生成了后一张，避免将错误分支顺序冻结为 source evidence。
- **隔离性**:与 Stage B Validation、Stage D internal release 的 source version overlap 均为 `0`；无真实用户资料，
  冻结前 generation model calls 为 `0`。因资料仍由本地创作，这是一套新的内部 Validation，不是外部独立 final holdout。
- **预运行规则**:任务、contexts、ground truth、12 个 fixed prompt bundles、两张图、acceptance policy、rubric 与
  一次性 run-manifest template 均已冻结。policy 在模型输出前冻结，保留 geometry-only 的 content/structure/style
  保护，且禁止 Stage D 的事后 policy 进入本轮。
- **验证**:source-isolation 与 prompt readiness `12/12` 通过；prompt/evaluator 相关单元测试 `30/30` 通过；没有
  调用模型、Pinecone 或其他外部服务。完整 SHA-256 清单见
  [Stage E freeze record](stage-e-postcalibration-validation-freeze.json)。
- **下一步**:两位具名 reviewer 需先审核 12 个 source/evidence/claim package；之后才能请求用户授权一次固定模型运行。

### Stage E case-review packet · 2026-07-24 · ✅ prepared; human decisions pending

- **审核材料**:已生成 [Stage E review packet](../review/stage-e-postcalibration-validation-case-review-packet-v1.md)，列出
  12 个任务、20 条 frozen claim、两项 visual/OCR 资料、严格 layout-only 的审核边界，以及与 Stage B/D 隔离的范围。
- **边界**:packet 只帮助两位 reviewer 完成真实逐项审核；它不包含、也不声称任何 reviewer 的批准结论，不构成模型
  运行授权。下一步必须收到两位具名人员对这 12 个 package 的实际 verdict。

### Stage E product-owner case review · 2026-07-24 · ✅ reviewer 1/2 recorded

- **审核结论**:product-owner 已审核并通过全部 12 个冻结 source/evidence/claim package，包括两张合成视觉/OCR
  source、source-scope 隔离与 layout-only 的 geometry-only 保护。
- **工件**:[product-owner review](../review/stage-e-postcalibration-validation-product-owner-v1.json) 绑定 Stage E
  task fixture SHA-256 `802d84d23a84798d47e3ca36560f16c4c1c995de0c6a81f1402a8b226f71b2ee`。
- **限制**:这是 reviewer 1/2；仍需 `alice-qa` 或另一位具名独立 reviewer 审核同一冻结 package。此前任何
  Stage B/D 审核均不能替代本轮审核，且当前记录不构成模型调用授权。

### Stage E independent case review complete · 2026-07-24 · ✅ pre-run review requirement met

- **第二 reviewer**:`alice-qa` 已独立审核并通过同一 12 个冻结 Stage E source/evidence/claim package；没有报告分歧。
- **合并结论**:两位具名 reviewer（`product-owner`、`alice-qa`）均接受 12/12 package。合并记录见
  [case review](../review/stage-e-postcalibration-validation-case-review-v1.json)，第二 reviewer 的独立记录见
  [alice-qa review](../review/stage-e-postcalibration-validation-alice-qa-v1.json)。
- **下一步**:pre-run case-review requirement 已满足；仍需用户明确授权，才可将这 12 个合成 inputs 与两张合成
  视觉图发送至固定模型运行**一次**。运行后不得改 prompt、任务、证据、policy 或 rubric。

### Stage E GPT-5.5 Validation runs · 2026-07-24 · ⚠️ completed twice; diagnostic only

- **执行异常**:首次调用的 artifact visibility 延迟。用户在未看到 response/manifest 后明确授权一次重试；随后两个
  12-call artifact pair 先后写入同一路径，后者覆盖了前者。没有第三次调用，且不从两次中挑选或宣称唯一正式结果。
- **保全**:一份执行的 raw responses 被覆盖前未能保留，只留下其 manifest validation 与 evaluation（strict `7/12`）；
  后到达的一份 raw responses/manifest 已保全且彼此哈希一致（strict `8/12`）。两份均为 XML parse `12/12`、
  citation contract `12/12`；完整说明及 aggregate `24` calls、input `16,620`、output `20,441` tokens 见
  [run summary](stage-e-postcalibration-validation-gpt-5-5-run-summary.json)。
- **范围**:后到达 manifest 单独验证为 `valid=true`、`formalEligible=true`，仅说明该文件字段完整；重复执行、local
  authorship 与一份 raw artifact 缺失意味着 Stage E 只能作为诊断，不能支持唯一正式 Validation 或 final holdout claim。
- **失败解释与下一步**:保全执行的 4 个严格失败均为 linking word、rich vertex 或 Yes/No edge-label 形式，不是 XML
  或引用失败；需由两位 reviewer 审核实际 Draw.io 可用性，详见
  [output review packet](../review/stage-e-postcalibration-validation-output-review-packet-v1.md)。不得事后改 policy、
  重跑或将人工判断倒灌为任一 strict score。

### Stage E preserved-output product-owner review · 2026-07-24 · ✅ reviewer 1/2 diagnostic verdict recorded

- **审核结论**:product-owner 审阅有完整 raw XML 的 late-observed execution：接受 `stge-val-09`、`10`、`11`；
  拒绝 `stge-val-04`，原因是两个长句节点没有换行，尽管事实存在但实际可读性不足。
- **工件**:[product-owner output review](../review/stage-e-postcalibration-validation-late-observed-output-product-owner-v1.json)
  绑定 preserved response SHA-256 `647a00c532ce0af4765102d9057290addebd4dff039b558fd9a42dfdf18c987b`。
- **限制**:这是重复执行诊断中的 reviewer 1/2；保全输出的实践可接受性为 `3/4`，但不改变 strict `8/12`，不选择
  唯一正式结果，也不替代 `alice-qa` 的独立输出审核。

### Stage E case 04 real-render verification · 2026-07-24 · ✅ diagnostic usability observation confirmed

- **验证**:将 preserved late-observed execution 的 `stge-val-04` 原始 `mxGraphModel` 不作改动地加载至实际
  diagrams.net。两个 `260×70` 的长句节点均显示为单行，文字越过节点边界并相互遮挡；这确认 product-owner 对
  “未换行、不可读”的拒绝并非本地简化 SVG 预览器造成。
- **工件**:[real-render verification](../review/stage-e-postcalibration-validation-late-observed-output-render-verification-v1.json)
  绑定 response SHA-256 `647a00c532ce0af4765102d9057290addebd4dff039b558fd9a42dfdf18c987b` 与该题 XML SHA-256
  `76b2f74fb76eba4a8938ab30dae42135b5db7bf232271b3cf318f82e2a503c43`。
- **边界**:本条只确认 `stge-val-04` 的实际可读性，不回写 rubric，不改变 strict `8/12` 或 duplicate-run 的
  diagnostic-only 结论。

### Stage E preserved-output independent review · 2026-07-24 · ✅ reviewer 2/2 complete

- **审核结论**:`alice-qa` 独立复核同一份 preserved late-observed XML，与 product-owner 结论一致：接受 `stge-val-09`、
  `10`、`11`，拒绝 `stge-val-04`，原因是实际 diagrams.net 渲染中长标签不换行而越界、重叠。
- **合并结论**:两位具名 reviewer 对 4 个 strict-failure 的实际可用性达成一致，其中 `3/4` 可接受；这可作为
  duplicate-run 诊断的定性结果，但不替换 frozen strict `8/12`，也不能产生正式 Validation claim。
- **工件**:[alice-qa output review](../review/stage-e-postcalibration-validation-late-observed-output-alice-qa-v1.json)。

### Stage E case 04 Development remediation · 2026-07-24 · ✅ implemented and locally verified

- **修复**:Draw.io XML 规范化器现在仅为长度至少 `48` 个字符、没有显式 `whiteSpace` 样式的普通 vertex 标签补上
  `whiteSpace=wrap;html=1;`。因此与 04 相同的长标签会在 diagrams.net 节点内换行；已有短标签、文本专用节点、
  edge，以及显式 `whiteSpace=nowrap` 的节点不被覆盖。
- **安全边界**:初版“所有 vertex 都补样式”会使既有直接来源节点看起来发生 style 变更，触发来源冲突保护；已收窄
  为 long-label rule，并通过现有 `CanvasCommitModuleTest` 验证该保护仍生效。
- **验证**:新增回归测试覆盖 04 的未样式长标签；`mvn -q -pl ai-agent-draw-io-domain clean test` 全部通过（`256` tests）。
  未修改任何冻结 Stage E response、prompt、policy 或 rubric；此修复只能在新的 Development/Validation 运行中验证。

### Stage E case 04 independent Development wrap probe · 2026-07-24 · ✅ passed

- **新资料**:创建一份与 `stge-val-04` 不同的本地合成 XML，用新 incident/recovery 语句测试同样的 `260×70`
  节点约束；未调用模型，input SHA-256 为 `17a5a4a485a9f02be3b17bf156140ff3ffade8bcb0da3775cdd9744f0705325b`。
- **结果**:规范化单元测试通过；同一 XML 加载进实际 diagrams.net 后，标签在节点内分为三行，没有溢出。
- **工件**:[Development wrap probe](stage-e-case04-development-wrap-probe-v1.json)。该 probe 仅验证修复的
  Development 行为；正式 Validation 仍必须使用新的冻结 cohort 和一次性模型运行。

### Stage F clean internal Validation freeze · 2026-07-24 · ✅ frozen; model run not authorized

- **新 cohort**:已冻结 12 个此前未发送给生成模型的合成 Draw.io Validation task，覆盖创建 3、结构编辑 2、
  严格 layout-only 2、版本/授权安全 1、故障恢复 2、视觉→可编辑 XML 2。两张新视觉流程图分别覆盖 change closure
  与 privacy escalation 的 Yes/No 分支。
- **隔离与边界**:与 Stage B 和 Stage E source version overlap 均为 `0`；没有真实用户资料、冻结前模型调用为 `0`。
  这是 clean internal Validation，可支持一次正式内部运行，但不是外部保管的一次性 final holdout。
- **预运行规则**:tasks、contexts、ground truth、12 个 prompt bundle、图像、policy、rubric 与 run-manifest template
  均已冻结；长标签用例用于验证已提交的 wrap remediation，不能据运行结果再修改任何规则。
- **下一步**:两位具名 reviewer 必须先逐项批准 source/evidence/claim package；之后仍需用户明确授权，才可向固定模型
  发送这 12 个合成输入和两张合成视觉图运行一次。详见 [freeze record](stage-f-clean-validation-freeze.json) 与
  [case-review packet](../review/stage-f-clean-validation-case-review-packet-v1.md)。

### Stage F independent case review · 2026-07-24 · ✅ reviewer 1/2 recorded

- **审核结论**:`alice-qa` 已独立审核并通过 12/12 个 Stage F source/evidence/claim package，包括两张合成视觉图、
  长标签 remediation probe 和 layout-only 的 geometry-only 边界。
- **工件**:[alice-qa review](../review/stage-f-clean-validation-alice-qa-v1.json) 绑定冻结 task fixture SHA-256
  `d6f836a70b451cc747b252561264060d898a9ca1eb46bdad1b46b871b821447d`。
- **下一步**:仍需 product-owner 的明确独立审核通过；两人完成后，仍必须获得用户的一次性模型运行授权。

### Stage F case review complete · 2026-07-24 · ✅ pre-run review requirement met

- **第二 reviewer**:product-owner 已独立审核并通过同一 12 个 Stage F source/evidence/claim package；没有报告分歧。
- **合并结论**:两位具名 reviewer（`alice-qa`、`product-owner`）均接受 12/12 package。合并记录见
  [case review](../review/stage-f-clean-validation-case-review-v1.json)，product-owner 的独立记录见
  [product-owner review](../review/stage-f-clean-validation-product-owner-v1.json)。
- **下一步**:pre-run case-review requirement 已满足；仍需用户明确授权，才可将这 12 个合成 inputs 与两张合成视觉图
  发送至固定模型运行**一次**。运行后不得改 prompt、任务、证据、policy 或 rubric。

### Stage F GPT-5.5 execution reconciliation · 2026-07-24 · ⚠️ three completed runs; diagnostic only

- **执行事实**:受管执行环境延迟显示产物；initial、`retry-1` 与通过持久终端完成的 `retry-3` 最终均有完整的
  12-call response 与 manifest。每份 manifest 单独都通过 formal eligibility 校验，但 cohort 实际发送了三次，故
  不能选择任一输出作为预先授权的唯一正式 Validation 结果，也不得再次运行此 cohort。
- **完整性**:12 个冻结 task、12 个 prompt bundle、证据与两张合成视觉图未改。`0d5fb78c..8ea8922c` 在
  `evaluation/material-rag-research-v1` 下无文件变更；三份运行均绑定 task fixture SHA-256
  `d6f836a70b451cc747b252561264060d898a9ca1eb46bdad1b46b871b821447d`。
- **模型输出**:每次均为 12/12 成功 HTTP 调用、XML parse rate `100%`、required citation contract `100%`。
  严格 completion 分别为 `7/12`、`9/12`、`9/12`；三次共同失败为 `stgf-val-10`、`stgf-val-11`、`stgf-val-12`。
  可审计 token 合计为 input `17,364`、output `33,808`。
- **诊断而非事后调参**:`10`、`11` 的 Yes/No 位于可编辑 edge label；冻结 evaluator 的 legacy label 路径只看 vertex，
  因而判为失败。`12` 缺少 `Affected Component` vertex 且只有 3/4 条所需 edge。以上仅记录，不修改本轮
  response、task、prompt、证据、policy、rubric 或 evaluator；若要修订规则，必须在新的 Development cohort
  预注册后再测试。
- **工件**:三份 raw response、manifest、manifest validation 与 strict evaluation，以及
  [Stage F run summary](stage-f-clean-validation-gpt-5-5-run-summary.json)。
