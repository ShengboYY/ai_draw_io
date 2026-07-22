# E4b 图册多来源 source diversity vs ranked raw

日期：2026-07-22。状态：**Development 可比较；候选不晋级**。Validation 与 Holdout 均保持密封。

## 范围与冻结控制

- 此次使用独立 `e4-chartbook-v1` profile：26 个 Development 案例，每个案例挂载四份长资料、至少两份
  gold 来源，并显式声明两个同 split 未挂载干扰来源。fixture 审计全部通过。
- 基线为 `ranked-raw-v1`，候选为
  `source-diversity-v1:evidence-dedup-v1:top10-max4-per-source:rank-fill`：先按 E4a 证据去重，再在
  top 10 对单一来源最多保留 4 条，替代来源不足时回填原始排序。
- 运行提交 `72f817ed`，E1 `canonical-v5`、`flat-leaf-v1`、原始 query、dense-only、
  `multilingual-e5-large`、生产 tokenizer、每案例同一 Pinecone top-80 与相同 mounted-source filter
  均被冻结；两臂都只从同一 top-80 产生前 40。
- 运行锁为 `2026-07-22-e4b-run-corpus-lock.json`，SHA-256
  `e5c8b425df70492383e3e3cff1259d5d1b5053b8e2274f984416c2e109659bc1`。
- 两次意外重叠的启动使用不同 run prefix；最终归档的是完整的第二次成对输出。两个 prefix 随后均以
  精确 cleanup 查询确认残留向量为 **0**，因此只对这一次确定、可比较的输出计分。

## Development 配对结果

| 指标 | ranked raw | source diversity | 配对差值 | 95% CI |
|---|---:|---:|---:|:---:|
| Recall@1 | 0.000 | 0.000 | 0.000 | [0.000, 0.000] |
| Recall@5 | 0.115 | 0.154 | +0.038 | [0.000, 0.115] |
| Recall@10 | 0.231 | 0.192 | **-0.038** | [-0.115, 0.000] |
| Recall@40 | 0.385 | 0.385 | 0.000 | [0.000, 0.000] |
| MRR@10 | 0.0459 | 0.0442 | -0.0016 | [-0.0115, 0.0066] |

Mapping 两臂相同，为 20/26=0.769。候选重排了 21/26=**80.8%** 的前 40；没有替换候选集合，故
removed/replacement positions 均为 0。这里的 activation 以“前 40 顺序不同”计，而不是仅统计移除：
这与预注册的“top-40 改变”一致，也能正确观察只重排、不删除的来源配额策略。

来源指标则显著朝预期方向改变：mounted coverage@10 **0.683→0.856**（+0.173）；unique sources@10
**2.731→3.423**；max-source-share@10 **0.619→0.400**（-0.219）；gold-source Recall@10
**0.423→0.615**（+0.192）。两臂的 gold-source Recall@40 均为 0.962，且未挂载来源泄漏位置均为
**0**。

## 门槛与诊断

Development 的 activation、零泄漏、gold-source recall 和来源覆盖/集中度条件均满足，MRR 的配对区间也
不支持显著下降。然而证据 Recall@10 由 0.231 降至 0.192，损失 **3.85pp**，超过预注册允许的 2pp。
唯一跨出 top 10 的案例是英文 `e4cb-dev-025`（rank 10→11）；`e4cb-dev-015` 从 7→5，但不足以抵消。
因此这不是“候选没有实际激活”，而是固定的每来源上限确实用更丰富的来源覆盖交换了早期精确证据排序。

## 决策

**不晋级 `source-diversity-v1`，不运行 Validation。** 保留 `ranked-raw-v1`、E1 canonical、flat leaf、
original query 与 dense retrieval。E4 现在已对单资料去重和真正的图册多来源配额都取得直接证据：前者
未在 Validation 复现，后者在 Development 违反证据 Recall@10 的硬门槛。下一阶段应进入 E5 reranking；
若未来重新研究来源多样性，应单独预注册一个能保留 query-specific evidence rank 的软重排或意图门控
策略，而不是改变本次已拒绝的固定 4-per-source cap。

原始输出为 `2026-07-22-e4b-ranked-raw-development-raw.json` 与
`2026-07-22-e4b-source-diversity-development-raw.json`；配对统计为
`2026-07-22-e4b-development-comparison.json`。
