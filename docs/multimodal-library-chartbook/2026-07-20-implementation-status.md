# 多模态资料库与图表册：实施状态

> 更新日期：2026-07-28
> 当前分支：`codex/multimodal-library-chartbook`

## 已完成阶段

| Work package | 状态 | 提交/交付 |
| --- | --- | --- |
| WP0：开关、匿名凭证与模块骨架 | 已完成 | `135ed64a` |
| WP1：DDD 领域基础、MySQL schema、Pinecone 边界 | 已完成 | `3c67fbe0` |
| WP2：S3 上传与安全 Worker | 已完成 | `82e22f07` |
| WP3A：内容去重、资料物化与 original promote | 已完成 | `23c58260` |
| WP3B：PDF/图片解析、选择性 OCR 与 canonical page | 已完成 | `8fadb343` |
| WP3C-A：文档结构领域核心 | 已完成 | `af48c3cf` |
| WP3C-B1：结构编排与持久化 | 已完成 | `8793495d` |
| WP3C-B2a：本地视觉候选与 crop manifest | 已完成 | 当前 WP3C-B2a 阶段提交 |
| WP3C-B2b：Evidence Unit、区域与关系 | 已完成 | 当前 WP3C-B2b 阶段提交 |
| WP3C-B3a：Retrieval Chunk 与 lexical 领域投影 | 已完成 | `5ef60be0` |
| WP3C-B3b1：固定 multilingual-e5 tokenizer runtime | 已完成 | `8a424eb7` |
| WP3C-B3b2：Retrieval 投影编排与原子持久化 | 已完成 | 当前 WP3C-B3b2 阶段提交 |
| WP3C-B3c：向量 generation、批次嵌入与 Pinecone 投影 | 已完成 | 当前 WP3C-B3c 阶段提交 |

## WP2 交付范围

- `POST /api/v1/material-uploads`、`POST /{uploadId}/complete`、`GET /{uploadId}`；Owner 只从服务端凭证解析。
- 上传 init/complete 幂等；complete 固定首次成功的 S3 `VersionId`，同一事务写 pin 与唯一 intake job。
- 上传配额、匿名 workspace/IP 小时速率、账号字节预留和处理并发通过 Owner 级数据库锁串行决定。
- SigV4 Browser POST Policy 只允许精确 bucket/key/MIME/SSE/upload-id 和受限大小，不暴露文件名或 Owner ID。
- 独立可执行 ingestion worker；MySQL `SKIP LOCKED` claim、heartbeat、lease expiry reaper 与 fence token 提交。
- 过期且未完成的 POST policy 不再占用上传容量；可重试 Worker 错误按 10 秒、60 秒、5 分钟最多重试 3 次，随后稳定失败。
- WP2 安全处理器只消费 `VALIDATE_OWNERSHIP`；安全通过后在同一事务记录 `security_status=VALIDATED` 并排入 `RESOLVE_CONTENT_DEDUP`。
- S3 固定版本流式落到 Worker 临时文件；重新计算实际 size/SHA-256，ClamAV `INSTREAM` 扫描后才进行真实类型和结构校验。
- 首版允许 PDF、PNG、JPEG；拒绝 EICAR/恶意软件、声明 hash/MIME 不一致、加密或主动内容 PDF、多帧/超像素图片和超页数 PDF。
- quarantine/materials 私有版本化 bucket、最小 IAM、TLS-only bucket policy、Worker + ClamAV ECS task 示例、受限 clamd 配置和非 root Worker 镜像。
- 功能默认关闭；关闭时不创建上传 Controller 或 AWS 客户端，不影响原有纯文本绘图路径。Worker 的 WP3A stages 还由 `MATERIAL_MATERIALIZATION_ENABLED` 独立控制；关闭时不创建 promotion/handler bean、无需 `MATERIALS_BUCKET`，仍可保留 WP2 安全校验。

## 安全边界

WP2 不把文件复制到正式 materials bucket，也不提供预览。安全通过的上传仍处于 `PROCESSING`，只有 `security_status=VALIDATED`；WP3A 必须消费后续 job，完成物化并固定正式对象 VersionId 后，upload 才进入 `SUCCEEDED`。Revision 仍为 `PROCESSING`，在后续 publish 之前不能参与检索或引用。

## WP3A 交付范围

- 普通上传以 `(owner_type, owner_key, actual_sha256, actual_size)` 去重；命中时复用同 Owner 的 Material/Version 并幂等增加 scope link，不泄露跨 Owner 内容身份。
- 显式“上传新版本”保持目标 Material 边界：目标已有同 blob 时无变化；blob 只存在于其他 Material 时复用物理 blob，但在目标 Material 创建新 Version/ProcessingRevision。
- `RESOLVE_CONTENT_DEDUP` 与 `MATERIALIZE_VERSION_AND_REVISION` 合并为一个 fenced MySQL 事务，原子提交 blob winner、Material/Version/Revision、scope link、upload correlation 和唯一下一 job。
- 新内容排入 `PROMOTE_ORIGINAL`；Worker 从固定 quarantine VersionId 服务端复制到私有 materials bucket，要求 S3 计算并返回 SHA-256，校验大小、identity metadata 和 checksum 后持久化正式对象 VersionId/ETag/checksum。
- 正式 original 的 S3 VersionId 首次成功后不可覆盖；并发共享上传若观察到 `AVAILABLE` blob，只复用固定对象并完成自己的 upload，不再次 copy。
- Promotion 的读取与提交同时绑定 job target/stage/fence、upload correlation、Material `ACTIVE + lifecycle_generation + expires_at` 和 Revision `PROCESSING`；迟到任务不能在资料删除、到期或代次变化后复活内容。
- 并发 copy 的 loser、checksum 验证失败或 DB commit 失败时，Worker 按精确 destination VersionId best-effort 清理未发布对象；正式 pin 不受清理影响。
- 已存在的 `AVAILABLE` blob 直接排入 `EXTRACT_NATIVE`；正在 promote 的共享 blob 可由任一同内容上传安全重试，不会留下永久 `PROCESSING` upload。
- 临时会话资料从创建时获得 24 小时 TTL；资料库、图表册和图表 scope 的上传直接创建 retained Material，不经过虚假的临时会话归属。
- 已物化 upload 不再与 content blob 重复计算存储/文件配额；Worker 现在只领取安全校验、内容物化和 original promote 三类已实现 stage。

部署时必须依次应用 WP1、WP2、WP3A migration，再更新并启用 Worker；WP3A 代码会读取新增的 upload correlation 与正式对象 pin 字段，不能先于 migration 部署。

## WP3B 交付范围

- PDFBox 3 从固定 formal original `VersionId` 提取 PDF 原生文字、阅读顺序、归一化页面坐标、placed raster region 和质量特征；block-local native confidence 同时考虑 Unicode 异常、重复 glyph、source-map 覆盖和阅读顺序确定性，使损坏 native block 能被更可靠的 OCR block 替换。旋转 PDF 使用显示方向的宽高/坐标。PDF 与单张图片都生成 lossless page image，JPEG EXIF orientation 自动校正并统一到 JVM sRGB。
- OCR 候选由领域策略决定：图片必做 OCR；PDF 在有效字符、Unicode 异常、重复 glyph、文字覆盖率不达标，或健康数字正文旁存在达到面积阈值的局部 raster region 时做 OCR。正文健康时不会把 OCR 重复文本拼接进去。
- Tesseract 通过固定 `ProcessBuilder` argv 调用，不经过 shell；首版安装并强制使用 `eng+chi_sim`，启动时拒绝与当前 golden 校准曲线不匹配的语言配置，解析 TSV word bbox/置信度，并把均值低于 `0.70` 的页标成低置信。
- canonical page 保留 native/OCR 来源、block 阅读顺序、质量特征及 source map；native 映射到 PDF glyph bbox，OCR 映射到 word bbox。混合页按 block/bbox 对齐后使用绑定 `eng+chi_sim` golden 基线的版本化单调校准曲线择优，保留健康 native block 并补入不重叠的扫描区域，不做整页二选一。canonicalizer 支持确定性的双栏阅读顺序，并标记顶部/底部 boilerplate 位置候选；跨页 60% 重复确认由紧随其后的 `BUILD_DOCUMENT_STRUCTURE` 完成，避免把单页章节标题误删。`MERGE_NATIVE_AND_OCR_BLOCKS` 是 `NORMALIZE_CANONICAL_PAGES` 内的同一确定性操作，不创建无意义的中间 job。
- `native-extraction`、`raw-extraction`、`canonical-page` 和 page image 都以 immutable S3 object 存储；MySQL `material_page_artifact` 固定 object `VersionId`、SHA-256、大小和类型，读取时再次校验内容身份。
- `EXTRACT_NATIVE -> OCR_SELECTED_PAGES（按需） -> NORMALIZE_CANONICAL_PAGES` 的每次提交同时校验 job stage/fence、租约、Material lifecycle generation/TTL 和 revision generation，并在同一 MySQL 事务写页状态、artifact pin 与唯一下一 job；迟到 Worker 的对象不能覆盖权威状态。未获得 DB pin 的 immutable derived object 暂留为 orphan，不做可能误删并发 winner 的即时删除；后续 reconciliation 仅在超过 24 小时且确认没有 manifest/DB pin 后按精确 VersionId 清理。
- OCR 与 canonical job 使用 `work_key=page:{pageNo}`，单页失败只重试该页；最后一个 canonical 页在 revision barrier 后才排入 `BUILD_DOCUMENT_STRUCTURE`。各 stage fingerprint 包含上游 artifact SHA-256、实际 render DPI、Tesseract runtime/executable/languages/timeout、OCR 选择阈值、质量校准版本和 canonical 配置；Worker 在执行前同时校验 job input 与 Revision 持久化 profile，结构 stage 还包含按页排序的全部 canonical hash。Revision 的 parser/cleaner/OCR/schema 审计列由同一运行 profile 生成受限长度版本标识，不再写死与实际 Worker 不一致的值。队列对 promotion 和所有 revision job 做 processing-profile 路由，滚动发布时新 Worker 不会领取旧 profile 的任务。
- Worker 镜像增加 headless AWT 与 Tesseract 英文/简体中文语言包；`MATERIAL_DOCUMENT_PROCESSING_ENABLED` 独立控制 WP3B stage，默认关闭。关闭后不会领取解析/OCR job，也不影响原有纯文本输入绘图。
- 2026-07-19 已按 `linux/amd64` 实际重建 Worker：Tesseract runtime 为 `4.1.1`，包版本为 `tesseract-ocr=4.1.1-2.1build1`，镜像中已验证 `eng`、`chi_sim` 与 `osd`。镜像 ENV 显式固定 `TESSERACT_LANGUAGES=eng+chi_sim` 和 `TESSERACT_RUNTIME_VERSION=tesseract-4.1.1`；开启文档处理时 Worker 会在启动阶段核对真实 runtime 与语言包，ECS 不应覆盖这两个值。
- 当前环境没有 AWS profile/credentials：本地 MySQL migration 与本地 `linux/amd64` 镜像验收已完成，但生产 RDS migration task、ECR push、ECS 两阶段发布及生产开关尚未执行，不应将本地结果标记为线上部署完成。
- WP3B 内部页图采用 lossless PNG，优先保证 OCR 输入稳定；产品预览所需的受限 WebP 在后续视觉/预览阶段从固定 PNG 派生，不改变 canonical/source identity。

部署时在 WP3A migration 之后应用 `2026-07-22-create-document-processing-artifacts.sql`，重新构建 Worker 镜像，再启用 `MATERIAL_DOCUMENT_PROCESSING_ENABLED=true`。旧 Worker 镜像没有 Tesseract，不应提前打开该开关。后续改变 render/OCR/canonical 配置时应让旧 profile Worker 先排空其 promotion/revision job，再缩容；不能一次性替换全部旧 Worker，否则旧 Revision 会按设计保持排队而不是由不匹配的新 Worker 处理。

## 验证

- EICAR 在恶意软件阶段终止，且不会提交安全通过状态。
- 带 JavaScript OpenAction 的 PDF 被归类为 `REJECTED_SECURITY`。
- ClamAV INSTREAM 长度帧由无网络权限的确定性协议测试覆盖。
- 队列测试覆盖租约过期重新领取、旧 fence 不可提交，以及 Worker stage 过滤。
- 领域测试覆盖普通去重、显式新版本、目标同内容 no-op 和 retained Material 创建；基础设施测试覆盖原子物化分支和精确 S3 source VersionId promote。
- Maven 全 reactor 测试通过；MyBatis XML 通过 XML 语法检查，ECS JSON 通过 JSON 解析检查。
- WP3B 增加 native/OCR 选择、OCR word source map、PDF/图片解析、Tesseract 安全 argv、S3 exact-version artifact、fenced 三阶段编排和 migration contract 测试；全 reactor 继续通过。

## WP3C-A 交付范围

- 新增确定性的 `DocumentStructureBuilder` 富领域服务，不把跨页规则放入 Worker 或 MySQL adapter。
- 顶部/底部位置候选按 NFC、空白和独立页码占位规范化；同一 band 文本至少出现在 `max(3, ceil(pageCount * 60%))` 个可比页面才确认为 boilerplate，少量章节标题不会被误判。
- 基于 canonical `HEADING` block 产生稳定 section identity、页范围和 structure hash；尚未识别出标题时创建覆盖整文的 root section。
- canonical page 保留 placed raster region；图片资料以整页区域作为视觉候选，PDF 使用真实 placed image bbox。视觉候选生成稳定 ID，并在几何距离不超过 15% 页高时关联最近图注。
- WP3C-A 只完成 byte-stable 的领域结构产物及测试；`BUILD_DOCUMENT_STRUCTURE` 的 exact-version artifact、fenced MySQL section persistence 和后续 `ANALYZE_VISUALS/BUILD_EVIDENCE_UNITS` 由 WP3C-B 接入。

## WP3C-B1 交付范围

- `BUILD_DOCUMENT_STRUCTURE` Worker 只读取当前 revision 固定的 canonical page `VersionId`，逐页续租并再次校验页号；profile 包含 `DocumentStructureBuilder` 的真实策略 fingerprint，按页排序的 canonical hash 与 job input fingerprint 不一致时拒绝处理。
- `DocumentStructureBuilder` 产物写入 immutable `document-structure.json.gz`；MySQL `material_revision_artifact` 固定 object key、`VersionId`、SHA-256、大小和类型，重试只能观察到相同内容。
- section manifest 与 structure artifact 在持有 job stage、lease/fence、Material lifecycle generation 和 revision generation 的同一事务中提交；稳定 section identity 以 `(revision_id, id)` 为数据库主键，不同 revision 可复用相同结构 ref，同一 revision 的不可变事实冲突时失败。
- 结构提交后 revision 进入 `VISUAL_ANALYSIS`，并仅排入目标 revision、固定 structure hash/artifact hash/profile 的唯一 `ANALYZE_VISUALS` successor。
- `2026-07-23-create-document-structure-artifacts.sql` 和后续修正 section identity scope 的 `2026-07-24-scope-document-section-identity.sql` 已在本地 MySQL 应用并记录 checksum；生产环境必须按顺序通过独立 migration release 应用后才能部署本阶段 Worker。

## WP3C-B2a 交付范围

- `ANALYZE_VISUALS` 只从固定 `DOCUMENT_STRUCTURE` 与 page image `VersionId` 读取，不向外部模型或第三方服务发送文件内容，仅访问受控 MySQL/S3；job input 绑定 structure hash、structure artifact hash 和完整 processing profile。
- 领域 `VisualCandidateSelectionPolicy` 默认最多选择 `min(12, ceil(pageCount * 15%))` 页、每页最多 3 个区域；caption 与区域面积用于确定性排序，manifest 同时记录候选总数和被预算跳过的数量，不伪装成全量视觉覆盖。
- Worker 按候选 bbox union 从 lossless page PNG 生成本地 lossless PNG crop，并限制解码像素与输出大小；crop 与 `visual-crop-manifest.json.gz` 都使用 revision-local immutable key。
- MySQL `material_visual_artifact` 固定每个 candidate 的 crop object key、`VersionId`、SHA-256、大小和类型；crop、manifest、页面视觉状态和唯一 `BUILD_EVIDENCE_UNITS` successor 在同一 fenced 事务提交。
- `2026-07-25-create-visual-crop-artifacts.sql` 已在本地 MySQL 应用并记录 checksum；生产必须在 `2026-07-23`、`2026-07-24` 之后执行。
- 当前 crop 是 Evidence 创建前的内部 PNG，不是最终可引用视觉证据，也不是产品 WebP preview；未取得视觉授权时不会调用 VLM。

## WP3C-B2b 交付范围

- 新增确定性的 `TextBlockKindPolicy` 与 `EvidenceUnitBuilder` 富领域服务：真实 PDF/OCR canonicalization 通过文字、几何和字体高度强信号识别 heading、list、caption；页级 canonical assembler 只把同来源、连续、几何相邻且分隔符/列数一致的至少两行合并为 table，单行 `|`/tab 仍是 paragraph，强 heading 几何优先于编号列表语法。Evidence 只从固定 canonical `display_text` 与视觉 crop 建立可引用边界。确认的 boilerplate 被排除，连续同源且缩进同层的列表最多 8 项成组，超过 2,000 字符的段落依次按句界、空白与 Unicode code-point 安全边界切分；表格只有在 canonical metadata 明确声明表头行时才建立表头关系，否则保留为普通表格行组。VLM 生成文字不进入 Evidence。
- 文本 Evidence 固定 revision、version、page、section、canonical artifact、来源 `NATIVE/OCR`、原文 SHA-256、source-map 字符区间和一个或多个 bbox；视觉 Evidence 固定原页面、candidate bbox 与 crop object `VersionId`。
- 领域层只在同一非空 section 内建立双向 `PREVIOUS_IN_SECTION/NEXT_IN_SECTION`，同时建立 `CAPTION_OF` 与 `TABLE_HEADER_FOR` relation，并把 heading Evidence 回填到对应 section；同页首 heading 前正文保持 unsectioned，不会错误连接到后续章节。`evidence-manifest.json.gz` 保存全部精确来源 pin 与确定性 evidence hash。
- `BUILD_EVIDENCE_UNITS` 只读取固定 structure、visual manifest 与 canonical page `VersionId`；共享、版本化的 `EvidenceBuildLimits` 同时注入 Handler 和 processing profile，gzip 解压、整文字符与 region 数均有硬预算，超限以 `DOCUMENT_PROCESSING_LIMIT_EXCEEDED` 永久失败而不重试。manifest、`evidence_unit`、`evidence_region`、`evidence_relation`、section heading 和唯一 `BUILD_RETRIEVAL_CHUNKS` successor 在同一 fenced 事务提交；MySQL JSON 以结构语义而非键顺序比较。
- `2026-07-26-pin-evidence-artifact-versions.sql` 给文本、视觉和未来 visual-analysis 引用补齐 exact object `VersionId` 及成对非空约束；已在本地 MySQL 应用，checksum 为 `1b086e6a64bfa4309ae7c90c1498484ec21835cac5a7e339cfd348a481be0282`。生产必须在 `2026-07-23`、`2026-07-24`、`2026-07-25` 之后执行。

## WP3C-B3a 交付范围

- 新增 `RetrievalChunkBuilder` 领域服务和带 fingerprint 的 `RetrievalTokenCounter` 端口；领域层不使用字符数近似代替真实 `multilingual-e5-large` tokenizer，也不允许供应商截断。
- 从固定 Evidence manifest 生成 citable leaf、同页同 section 的短块合并、句界/code-point 安全超限拆分、最多相邻一块的 bounded parent context、不可引用的 section bridge/document profile，以及显式 `PRIMARY/HEADER/CAPTION/REPRESENTATIVE` Evidence 映射。
- 检索文本中的章节标题、表头和图注都保留对应 Evidence identity；parent context 同时保存全部来源 Evidence IDs。无图注视觉与低质量/OCR Evidence 标为 `UNSEARCHABLE`，不会生成 lexical projection。
- word/CJK lexical shadow 与受限 exact identifier/number terms 均由确定性投影产生；bridge/profile 数量受 leaf chunk 20% 上限约束。所有 chunk ID、文本 SHA-256 和 manifest hash 可重复生成。

## WP3C-B3b2 交付范围

- processing profile 现在包含 Retrieval builder 和固定 tokenizer fingerprint；旧 profile 任务只会由匹配的旧 Worker 领取。`BUILD_RETRIEVAL_CHUNKS` 只读取 MySQL 固定的 Evidence manifest object `VersionId`，同时校验 revision/version、上游 artifact SHA-256、完整 processing profile 和 builder fingerprint。
- Worker 为每个 chunk 写入独立 immutable gzip JSON；有 parent window 时另写带完整 Evidence identities 的 parent artifact，并写入 revision 级 `retrieval-manifest.json.gz`。所有对象均使用 revision-local deterministic key，数据库固定 object key 与 `VersionId`。
- `retrieval_chunk`、`retrieval_chunk_evidence`、`retrieval_search_document`、`retrieval_exact_term`、Retrieval manifest pin 和唯一 `BUILD_LEXICAL_PROJECTION` coordinator successor 在同一个校验 job stage/lease/fence、Material lifecycle generation/TTL 与 revision generation 的 MySQL 事务提交。不可检索 chunk 不会创建 lexical rows，引用边界仍只来自显式 Evidence mapping。
- `2026-07-27-pin-retrieval-artifact-versions.sql` 为 retrieval text/parent object 补齐 exact `VersionId`，并把 Evidence mapping identity 修正为 `(retrieval_chunk_id, ordinal)`；checksum 为 `e90335c8aee058d9a711b8d75a55b8268481d2eef03bf20f1b0ad48ec1945a36`。生产必须在旧 migration 之后执行，并在新 Worker 开始领取 retrieval job 前完成。

## WP3C-B3c 交付范围

- 新增 generation-scoped `VectorProjectionPlanner` 富领域服务：generation identity 固定 Pinecone index/namespace、`multilingual-e5-large` 模型 fingerprint、1024 维 cosine contract 与 vector schema；仅 `DENSE_AND_LEXICAL` chunk 进入 embedding，lexical-only 与 unsearchable chunk 不会发往 Pinecone。
- `BUILD_LEXICAL_PROJECTION` 从 exact-version Retrieval manifest 确定性生成每批最多 96 条且受 UTF-8 payload 预算约束的计划；work key 固定为 `ig:{generation}:batch:{batch}`。Embedding 明确使用 passage input 与 `truncate=NONE`，返回模型 identity、数量和维度都在写入前校验；Pinecone 429/5xx 的 `Retry-After` 会进入 durable job backoff，无值时使用既有指数退避。
- 每个 passage embedding 先按 Owner HMAC、revision、retrieval text hash、tokenizer/model fingerprint 与 input type 查找 revision-local immutable S3 vector cache；重试和同 revision 重复文本不会再次调用推理。每批向量再写独立 immutable object，由 `UPSERT_VECTOR_BATCHES` job 幂等写 Pinecone。Pinecone 只保存向量、稳定 vector ID 与受限标量 metadata；不上传 Owner key、文件名或 chunk 原文。
- MySQL 分别保存不可变 generation 配置和 revision→generation 投影计划；tokenizer 属于 revision 投影而非全局 generation，因此 chunk schema 更新不会与仍兼容的 embedding index 冲突。另保存 batch identity/state、每个 chunk 的 projection fingerprint/state，以及 exact-version `projection-manifest.json.gz` pin。所有状态推进和 successor job 都校验 stage、lease/fence、Material lifecycle/TTL 与 revision generation；最终 batch 判定使用 revision row lock 串行化，避免并发 upsert 丢失 verifier。Pinecone 成功而 DB 提交失败时可按相同 vector ID 安全重试。
- lexical-only revision 也会生成零向量 projection manifest，不会被错误阻塞。`MATERIAL_VECTOR_PROJECTION_ENABLED` 是独立且默认关闭的 Worker 开关；关闭后不会实例化 Pinecone/HMAC 依赖或领取向量 stage，原有纯文本绘图路径不依赖向量服务。
- `2026-07-28-create-vector-projection-artifacts.sql` 增加 generation 配置、revision projection plan、vector batch 与 projection manifest 审计表，checksum 为 `320545ca02d54fb2cf0c6cd357b59a87bee03a7a88892946cb08ca78e28fc061`。生产必须先执行 migration 并配置 Pinecone host/API key、namespace 与 tenant HMAC secret；在下一阶段完成 publish gate 前保持向量开关关闭。

## 下一阶段

WP3C-B3d 将实现 `PUBLISH_REVISION` 的 publication gate：核验 projection manifest 的 exact S3 pin、MySQL projection 数量与 Pinecone generation readiness；首次 generation 准备完成后原子激活，已有 generation 则安全追加 revision，最后才把 Revision/Version 发布为可检索。该阶段同时补充显式 compatibility coordinator，使新写入和历史 active/pinned revision 可以在 ACTIVE/BUILDING generation 间建立双投影并完成 shadow gate。失败与回滚不能让半成品 revision 进入检索；generation 切换需要保留旧 generation，供既有图表与引用继续按原版本解析。
