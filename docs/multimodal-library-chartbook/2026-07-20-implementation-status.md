# 多模态资料库与图表册：实施状态

> 更新日期：2026-07-21
> 当前分支：`codex/multimodal-library-chartbook`

## 已完成阶段

| Work package | 状态 | 提交/交付 |
| --- | --- | --- |
| WP0：开关、匿名凭证与模块骨架 | 已完成 | `135ed64a` |
| WP1：DDD 领域基础、MySQL schema、Pinecone 边界 | 已完成 | `3c67fbe0` |
| WP2：S3 上传与安全 Worker | 已完成 | `82e22f07` |
| WP3A：内容去重、资料物化与 original promote | 已完成 | 当前 WP3A 阶段提交 |

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

## 验证

- EICAR 在恶意软件阶段终止，且不会提交安全通过状态。
- 带 JavaScript OpenAction 的 PDF 被归类为 `REJECTED_SECURITY`。
- ClamAV INSTREAM 长度帧由无网络权限的确定性协议测试覆盖。
- 队列测试覆盖租约过期重新领取、旧 fence 不可提交，以及 Worker stage 过滤。
- 领域测试覆盖普通去重、显式新版本、目标同内容 no-op 和 retained Material 创建；基础设施测试覆盖原子物化分支和精确 S3 source VersionId promote。
- Maven 全 reactor 测试通过；MyBatis XML 通过 XML 语法检查，ECS JSON 通过 JSON 解析检查。

## 下一阶段

WP3B 从 `EXTRACT_NATIVE` 开始，实现 PDFBox 原生文本/版面提取、图片与 PDF canonical page、OCR 候选和 Tesseract adapter，并为后续视觉分析与 Evidence 构建生成可重放的中间对象。
