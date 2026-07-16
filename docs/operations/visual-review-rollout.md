# Visual Reviewer / Drawer Loop 灰度手册

## 目标与边界

本手册只控制 VLM Reviewer 和回到同一 Drawer agent 的两轮有界修复。Reviewer 只提供 findings；Policy 决定是否继续；Mutation Gate 决定候选修改能否保存。二进制默认全部关闭；部署的首个灰度阶段显式打开 Reviewer。百分比变化不会自动扩量，必须由人工观察指标后调整。

分组键为 `ownerId:diagramId` 的稳定 SHA-256 bucket。同一张图在配置不变时始终处于同一 cohort。百分比被限制在 0–100；缺少 owner 或 diagram 时 fail closed。

## 分阶段启用

### 0. Reviewer-only 基线

```bash
ZIPP_VISUAL_REVIEW_ENABLED=true
ZIPP_VISUAL_REVIEW_AUTO_REPAIR_ENABLED=false
ZIPP_VISUAL_REVIEW_ROLLOUT_PERCENT=10
ZIPP_VISUAL_REVIEW_ROUND_1_ROLLOUT_PERCENT=0
ZIPP_VISUAL_REVIEW_ROUND_2_ROLLOUT_PERCENT=0
```

先从 10% Reviewer 流量观察可用率、决策分布、延迟和 token。确认正常后可逐步把 Reviewer 扩到 100%；这一阶段绝不回到 Drawer 修复。

### 1. Round 1 小流量

```bash
ZIPP_VISUAL_REVIEW_ENABLED=true
ZIPP_VISUAL_REVIEW_AUTO_REPAIR_ENABLED=true
ZIPP_VISUAL_REVIEW_ROLLOUT_PERCENT=100
ZIPP_VISUAL_REVIEW_ROUND_1_ROLLOUT_PERCENT=5
ZIPP_VISUAL_REVIEW_ROUND_2_ROLLOUT_PERCENT=0
```

Round 1 从 5% 开始。因为 repair cohort 同时受 Reviewer cohort 约束，不能把 repair 百分比理解为全站绝对流量。

### 2. 单独评估 Round 2

```bash
ZIPP_VISUAL_REVIEW_ROUND_1_ROLLOUT_PERCENT=25
ZIPP_VISUAL_REVIEW_ROUND_2_ROLLOUT_PERCENT=5
```

只有完成 round 1 且仍需修复的图才可能进入 round 2。先保持 round 1 百分比不变，再调整 round 2，避免两个变量同时变化。

## 指标口径

Prometheus 会把 Micrometer 的点号转成下划线并给 counter 添加 `_total`。

| 目的 | 指标与关键标签 |
| --- | --- |
| Review 决策/轮次 | `ai_agent_visual_review_total{stage,round,decision}` |
| Review 延迟 | `ai_agent_visual_review_latency_seconds{stage,round,decision}` |
| 修复结果 | `ai_agent_visual_repair_total{round,outcome}`；`continued` 表示进入 Drawer，其他结果包括 rollout/authorization/claim/budget 等阻断原因 |
| Mutation Gate | `ai_agent_canvas_mutation_total{purpose="vlm_repair",status,reason,repair_round}` |
| 预算耗尽 | `ai_agent_visual_repair_budget_exhausted_total{round}` |
| 最终复核 | `ai_agent_visual_verify_total{outcome}` |
| 修复后人工再编辑 | `ai_agent_visual_repair_manual_edit_total{round}`；只统计经 server lineage 验证后的首次编辑 |
| Router 对照 | `ai_agent_targeted_edge_router_total{version,outcome}` |
| VLM token/延迟 | 现有 `ai_agent_llm_tokens_total`、`ai_agent_llm_call_latency_seconds`，按 visual-review phase/provider/model 过滤 |

建议面板至少展示：每轮 `continued / repair requested`、Gate 按 reason 的拒绝分布、verify approve 比例、budget exhausted、首次人工再编辑/成功修复、Review 平均/最大延迟、LLM p50/p95 延迟、平均 token，以及 Router v1/v2 的 `routed`/`no_safe_candidate`。

人工再编辑率是当前浏览器会话观察到的“成功修复后的首次直接人工保存”，不是所有后续编辑次数。程序重载同一 XML 不携带 lineage；新 AI 修改会清除旧 lineage；后端只在当前版本和 hash 确实属于对应 repair run 时计数。

## 扩量检查

每个 cohort 至少覆盖完整业务峰谷并积累足够样本后再判断。初始建议门槛如下，团队有真实基线后应替换成基线相对门槛：

- 后端 Review `UNAVAILABLE` 不高于 2%。前端 `EXPORT_FAILED` 目前属于客户端错误监控信号，不作为本阶段自动扩量指标。
- VLM repair 的 Gate 拒绝率不高于 5%，且没有持续增长的 `scope_violation` 或语义变更类拒绝。
- VERIFY_ONLY 的 approve 比例达到 80% 以上。
- Review 延迟、LLM p95 延迟和单请求 token 在产品预算内。
- 修复后人工再编辑率不高于 reviewer-only/人工处理基线。
- Round 2 相比 round 1 有可见 verify 提升；若收益很小或人工再编辑率上升，round 2 保持 0。

这些是运维决策条件，不写入应用自动扩量逻辑。扩量时一次只调整一个百分比，并记录调整时间供 dashboard 对齐。

## 回滚

最快回滚自动修复：

```bash
ZIPP_VISUAL_REVIEW_AUTO_REPAIR_ENABLED=false
ZIPP_VISUAL_REVIEW_ROUND_1_ROLLOUT_PERCENT=0
ZIPP_VISUAL_REVIEW_ROUND_2_ROLLOUT_PERCENT=0
```

保留 Reviewer 便于继续收集只读证据。若 Reviewer 本身影响可用性，再设置：

```bash
ZIPP_VISUAL_REVIEW_ENABLED=false
```

Router v2 独立由 `zipp.canvas.targeted-edge-router-v2-enabled` 控制；它不随 Reviewer cohort 自动启用。遇到 route regression 时先关闭该开关并比较 `version=v1/v2` 指标，再决定修复或删除实现。
