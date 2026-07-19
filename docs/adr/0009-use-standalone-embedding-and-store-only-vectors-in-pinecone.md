---
status: accepted
---

# 使用独立 Embedding 调用并只向 Pinecone 写入向量

首版使用 Pinecone Inference 的 standalone `embed` 操作生成向量，再写入标准 dense vector index；不使用会把原文记录写入索引的 integrated embedding upsert。该方案仍复用同一供应商并保持实现简单，同时满足 Pinecone 中只保存向量和最少不透明元数据的数据最小化边界；代价是摄取和查询需要显式执行 embed 与 vector 操作，并自行校验模型、维度、切块和重试。

## 结果

- Pinecone metadata 不得包含文件名、完整正文、摘录、图片、S3 地址或用户可识别信息。
- `EmbeddingPort` 和 `RetrievalVectorIndex` 必须分离，Embedding 模型或供应商可以替换而不改变资料事实源。
- Embedding profile 属于 `rag_index_generation`/vector projection，不进入 Processing Revision fingerprint；模型升级为既有 Retrieval Chunk 建 compatibility projection，不重新解析或 OCR。
- chunk 超过模型上限时必须在本地失败并修正，使用 `truncate=NONE`，不能静默截断。
- Pinecone 推理仍属于外部数据处理，必须披露并在上线前核验供应商留存与训练条款。
