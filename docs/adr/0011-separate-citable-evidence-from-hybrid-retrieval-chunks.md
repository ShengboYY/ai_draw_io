---
status: accepted
---

# 分离可引用 Evidence 与混合检索 Chunk

首版将可定位、可回源、可引用的 `Evidence Unit` 与为召回优化的 `Retrieval Chunk` 分离。Retrieval Chunk 可以增加标题上下文、组合相邻 Evidence，或作为不可引用的章节/文档桥接单元；任何检索命中都必须映射回真实 Evidence Unit 后才能进入 Citation。

Agent RAG 同时使用 Pinecone dense 与现有 MySQL FULLTEXT/exact-term lexical 投影，通过 Weighted Reciprocal Rank Fusion 合并名次，再执行 MySQL 二次鉴权、本地特征重排、parent/relation 扩展、去重和 coverage-aware 选择。该方案提高专有名词、数字、中文短词、节点关系和宽泛绘图任务的召回与精度，同时不新增 OpenSearch、第二个向量库或常驻 reranker 服务。

## 结果

- `SECTION_BRIDGE` 与 `DOCUMENT_PROFILE` 只能帮助定位来源或章节，不能直接形成引用。
- Pinecone projection 挂在 Retrieval Chunk；业务 Citation 仍挂 Evidence Unit，索引策略变化不改变历史引用身份。
- Parent/stitched context 中每个送入模型的片段必须标为 `SUPPORT` 或 `CONTEXT_ONLY`；后者不能支持事实 claim，不能被持久化为实际引用。
- dense cosine 与 MySQL relevance 不直接线性相加；先使用基于 rank 的 RRF，再使用版本化、可解释的本地特征。
- 默认不调用额外远端 reranker 或 HyDE；只有本项目 locked evaluation 证明精度收益并满足延迟、成本、配额和隐私 Gate 后，才通过 port/feature flag 灰度。
- lexical 和 dense 的任何 hit 都必须经过相同的 AuthorizedSourceSet、MySQL 二次鉴权、read lease 和 S3 回源校验。
- 详细 chunk、query、candidate、rerank、Bundle 和评测参数以高精度 RAG 实现设计为准。
