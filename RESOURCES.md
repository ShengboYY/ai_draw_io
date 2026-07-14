# Production VLM Review Chain Resources

## Knowledge

- [本项目 VLM 实施方案](docs/superpowers/plans/2026-07-14-production-vlm-reviewer-implementation-plan.md)
  本次实现的需求、边界、decision policy 与分阶段上线标准。用于确认“为什么这样设计”。
- [生产 VLM Reviewer](ai-agent-draw-io/ai-agent-draw-io-domain/src/main/java/org/zipp/ai/domain/agent/service/visualreview/ChatCanvasVisualReviewer.java)
  图片输入、严格 schema v2、超时和 fail-open 行为的代码事实来源。
- [Review Orchestrator](ai-agent-draw-io/ai-agent-draw-io-trigger/src/main/java/org/zipp/ai/trigger/http/service/CanvasVisualReviewOrchestrator.java)
  版本校验、VLM 调用、决策、repair continuation 和 telemetry 的主流程。
- [draw.io Embed Mode — official](https://www.drawio.com/docs/reference/embed-mode/)
  官方 JSON postMessage、export action 与 response message 协议。用于理解 export `requestId` 回显关联。
- [MySQL 8.4 ALTER TABLE — official](https://dev.mysql.com/doc/refman/8.4/en/alter-table.html)
  官方 DDL 语义。用于审查和执行向前兼容 migration。

## Wisdom (Communities)

- 本项目的 code review 与 production eval 数据集
  先用仓库中的真实失败案例校准 reviewer，再讨论扩大自动修复范围；当前不依赖外部社区建议。
