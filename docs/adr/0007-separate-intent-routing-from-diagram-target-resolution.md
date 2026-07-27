---
status: accepted
---

# 将意图路由与图表目标解析分离

Intent Router 只根据用户请求、是否存在画布及经过服务端校验的轻量选择摘要决定主要动作、证据需求和工具权限，完整 Draw.io XML 不进入其模型输入。权限建立后，由独立的 Diagram Target Resolver 读取服务端保存的当前版本 XML，将已选 cell 或文字指代解析为具体节点/连线，并返回 `RESOLVED`、`AMBIGUOUS` 或 `NOT_FOUND`；后两种状态在检索或画布变更前要求用户澄清。该边界避免用户可控的画布内容影响工具权限，也避免 Intent Router 同时承担意图分类和实体消歧，代价是新增选择上下文、目标解析服务和候选高亮协议。

## 结果

- 客户端提交的 cell ID 和画布版本只能作为声明，服务端必须以当前用户的已保存画布重新校验。
- 用户已选择目标时优先按 cell ID 精确定位；未选择时才使用当前图表结构和用户文字解析指代。
- 多个候选或无法定位时不启动 Retrieval Router，也不修改画布；响应返回候选 cell 供界面高亮和用户确认。
- 当前 `IntentRoutingCommand` 可以继续携带 XML 供确定性代码判断画布是否存在，但 Intent Router 的 LLM 消息不得包含完整 XML。
