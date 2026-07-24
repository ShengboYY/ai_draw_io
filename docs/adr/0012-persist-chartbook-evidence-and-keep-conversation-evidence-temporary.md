---
status: accepted
supersedes: 0001-index-temporary-and-long-term-evidence-in-pinecone
---

# 持久索引 Chartbook 证据并保持 Conversation 证据临时

Draw.io 中的新上传文件首先属于当前 Conversation，并保持 `TEMPORARY` 生命周期。Conversation File 可以生成受 TTL 约束的 Evidence、Retrieval Chunk 和 MySQL lexical/exact 派生物，但不创建长期 Pinecone vector projection。

用户执行 `Add to Chartbook` 后，同一个 Material 转为 `RETAINED` 并增加 `CHARTBOOK` scope；系统不复制 Material、Version、Processing Revision 或原文件。具有 Chartbook 或其他既有 durable scope 的 retained revision 才具备长期向量投影资格。已有兼容投影必须复用，仅补建当前 generation 中缺失的投影。

## 请求来源

普通 AUTO 请求只从以下授权范围构造不可变 source snapshot：

1. 本轮新上传文件；
2. 当前 Conversation Files；
3. 当前 Diagram 固定资料；
4. 当前 Chartbook Shared Files。

AUTO 不包含整个 Personal Library。同一 version 出现在多个 scope 时只保留一次，并为每次 run 固定 exact Material Version 和 Processing Revision；重试不得漂移到更新的 version 或 revision。展示和冲突处理优先级为 `CHARTBOOK > DIAGRAM > CONVERSATION > LIBRARY`。MySQL 中的 owner、scope、lifecycle、retention 和 expiry 始终是授权事实源；Pinecone 命中不能单独授予访问权。

Direct 使用 snapshot 中 exact version 的原始或视觉产物，不依赖向量投影，也不等待文件达到 `SEARCHABLE`。Retrieval 只能检索 snapshot 中已经获得授权的版本：Chartbook Shared Files 可以使用 lexical + dense，Conversation Files 首版使用 lexical/exact 或受限的解析片段读取。

## 结果

- Conversation File 上传后在当前 Conversation 的后续请求中自动可用，不再依赖逐消息附件选择。
- Conversation 临时资料到期时，原文件、解析派生物和可能遗留的旧向量投影继续通过现有 read lease 与删除流程清理。
- `Add to Chartbook` 必须作为幂等的单一领域操作完成 retention、scope 和索引调度。
- Chartbook Shared File 的原文件可读但索引未完成时，Direct 仍然可用；Retrieval 可以在 dense 不可用时降级为 lexical。
- 移除 Chartbook scope 后，根据剩余 durable scope 和有效 Conversation scope 决定保留、恢复临时语义或进入删除流程。
- 既有 Library 和 Diagram durable 数据继续兼容，但 Personal Library 不参与普通 AUTO。
- 旧客户端的逐消息 selected IDs 和旧的两步 promote/add 调用保留一个稳定发布周期；兼容字段不能扩大服务器计算出的授权范围，随后在兼容清理阶段移除。
- 既有 run snapshot 保持不可变；本决策只影响新 run，不批量改写历史快照。

该决策接受 Conversation 长文档仅使用 lexical/exact 时可能出现的召回下降。只有真实使用数据证明临时语义检索确有必要时，才考虑增加 TTL-bound transient vector adapter；不得恢复默认将所有临时资料写入长期 Pinecone。
