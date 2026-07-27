---
status: accepted
---

# 将资料版本与处理修订分离

资料版本只表示用户提供的不可变内容；内容未变但解析、OCR、清洗、页面排除、视觉分析、切块或 lexical projection 方式改变时，在同一资料版本下创建新的不可变处理修订。Embedding model、dimension、metric 或 vector schema 的变化不创建处理修订，而是建立新的 Index Generation，并为既有 Retrieval Chunk 增加 compatibility projection。新修订成功发布后供后续检索使用，失败时继续使用原活动修订；历史引用固定到实际使用的资料版本和处理修订，避免系统升级悄悄改变既有图表的证据含义。

## 结果

- 活动修订通过一次原子切换发布，不能边处理边替换当前可用证据。
- Chunk schema 变化建立新处理修订；Embedding/index 变化建立新 Index Generation，二者不能共用一套“双写 revision”语义。
- 仍被历史引用使用的旧处理修订必须保留，直到引用重新验证或资料被永久删除。
- 重新处理不会触发“资料有新版本”提醒，但可以提示相关引用存在可重新验证的处理结果。
