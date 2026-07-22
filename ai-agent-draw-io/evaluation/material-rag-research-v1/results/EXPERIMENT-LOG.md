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
- **切片**:不只看总分,按语言/类别切片看,防止「整体赢、局部退化」。当前每切片样本偏小
  (n=8~44),**per-slice 只能看大趋势,不能当精确结论**(见 E1 validation)。
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
  16 项结构检查全 PASS,双人复核(AI 首轮 + 人工确认),**E0 lock FROZEN**。语言比例按数据集实际构成
  (中文场景文档偏多)定,每语言仍远超 per-slice 所需。
- **数据集 240→450 升级**:每类 case 补到 ≥50 以让 per-slice 统计可信(validation 曾证明 n=8~44 时切片结论
  不稳)。新增 5 篇跨领域 digital 文档 + 2 篇扫描件(补 ocr),用 ILP + `query-selection.json` 精确削减到 450。
  ⚠️ **E0/E1 的旧结果是在 240 语料上跑的,需在 450 语料上重跑**;test 的 projection 列表已加入 5 篇 expansion 文档。
- **守门套件**:**261**(总量超 plan 的 220)。新增 **图册收窄专项套件**(`guard_chartbook_scope`,测「图册内
  检索不得触达用户库中其他未挂载资料」这条收窄不变量)。各套件:authorization 58、versioning 36、
  abstention 40、visual-ocr 57、failure 18、chartbook_scope 12、regression 40——abstention 达标,其余接近
  plan 下限(差 2-4,pass/fail 判断已足够)。
- 生成确定性可复现;`query-selection.json`(ILP 削减)与 spec 模块纳入 provenance。31 篇文档、334 锚点。
- 关键提交:语料 `14141132`、E0 适配 `c8f4845e`、E1 晋级 `89f8bbfe`、E1 复核 `228eb416`、数据集 v-next(本次)。

---

## 实验时间线

> 注:下列 E0/E1 结果在 **240 语料(数据集 v1)** 上得到;数据集已升级到 **450(v-next)**,E0/E1 需在新语料重跑。

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

---

## 当前状态与下一步

- 已完成:E0 基线 → E1(表示层)晋级并复核。表示层这条路已走到 dense 上限(门槛边缘)。
- **下一步候选**:
  1. **E3 hybrid(推荐)**:dense+lexical 融合。E1 剩下的 miss 是精确标识符/时间戳/抽象规则,
     正是 BM25 能补、dense 补不上的 → 投入产出比最高,最可能稳过门槛。
  2. **E2 chunk 父子结构**:plan 顺序在 E3 前,或能顺带改善 Recall@40 与多证据。
  3. **补数据集**(进行中):每切片补到 ≥50,让后续 per-slice 结论可信。

## 开放问题 / 待办

- [ ] 核心集每切片补到 n≥50(validation 已证明当前切片不可信)。
- [x] 守门补 **「图册收窄」** 套件——已建 `guard_chartbook_scope`(12 例)。
- [x] 守门套件补齐——总量 261 超 plan 220;各套件接近下限(authz 58/ver 36/visualOcr 57/failure 18,差 2-4,可后续小补)。
- [ ] 英文单语切片在 dev 偏弱、在 val 偏强 —— 小样本噪声,补量后再判断是否真问题。
- [ ] E6/E7(上下文选择、生成引用)尚未开跑。

## 如何跑一个实验(运行手册)

1. **保证干净基线**:把不属于本实验的在途改动 `git stash push <files>`;确认 `git status` 干净。
2. **加载环境**(在 `ai-agent-draw-io/`):
   ```bash
   set -a; source .env; set +a
   export MATERIAL_RAG_TOKENIZER_PATH="$PWD/tmp/material-rag-tokenizer/tokenizer.json"
   export MATERIAL_RAG_RESEARCH_SPLIT=development   # 复核用 validation;holdout 只最后一次
   export MATERIAL_RAG_INDEX_WAIT_ATTEMPTS=240 MATERIAL_RAG_DELETE_WAIT_ATTEMPTS=120  # starter 层
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
