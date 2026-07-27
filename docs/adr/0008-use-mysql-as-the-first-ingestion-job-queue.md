---
status: accepted
---

# 首版使用 MySQL 持久任务表驱动摄取 Worker

首版通过 MySQL 8.4 的持久任务表、短事务 `SELECT ... FOR UPDATE SKIP LOCKED`、任务 lease 和 fence token 驱动独立摄取 Worker，不新增 SQS、Kafka 或 RabbitMQ。该选择适合当前很小的用户量，复用既有 RDS、事务和运维能力，并避免新增常驻基础设施成本；代价是必须控制轮询频率、保持认领事务很短，并监控队列积压和数据库负载。

## 结果

- 外部文件/模型/向量 I/O 不得持有数据库锁；Worker 认领任务后立即提交事务。
- job 完成、重试和心跳都必须带 lease owner 与 fence token，迟到 Worker 不能提交结果。
- 领域层通过 `ProcessingQueuePort` 使用队列；当持续积压或数据库负载证明需要时，可以替换为 SQS 而不改摄取状态机。
- Pinecone、VLM、OCR 或 Worker 队列健康不得成为普通文本绘图的应用 readiness 依赖。
