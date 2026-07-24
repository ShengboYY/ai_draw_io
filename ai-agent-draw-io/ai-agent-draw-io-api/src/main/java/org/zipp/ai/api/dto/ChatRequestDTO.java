package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class ChatRequestDTO {

    private String agentId;
    private String userId;
    private String sessionId;
    private String requestId;
    // Stable frontend assistant-message id used by the WP7 atomic answer transaction.
    private String responseMessageId;
    // Server-owned correlation id; controllers overwrite any client-provided value before use.
    private String runId;
    // Visual repair lineage is server-owned and lets logs/telemetry reconstruct the outer loop.
    private String sourceRunId;
    private String parentRunId;
    private Integer visualRepairRound;
    private String diagramId;
    private Long expectedVersion;
    private String expectedContentHash;
    private String message;
    private String canvasXml;
    private String canvasSummary;
    // WP5 source/selection declarations are opaque IDs; authorization and canvas validation stay server-side.
    // Current-message uploads are opaque IDs; the server resolves readiness and ownership before use.
    private java.util.List<String> attachmentUploadIds;
    private String sourceMode;
    // Optional direct-conversion preference; the server still verifies source readiness and authorization.
    private String sourceUseOverride;
    // Prior direct-image reason codes and bounded user choices; never treated as free-form prompt text.
    private java.util.List<DirectClarificationDTO> directClarifications;
    // Exact version from the confirmation event; the server rejects stale-image confirmation reuse.
    private String directConfirmationSourceVersionId;
    private java.util.List<String> selectedVersionIds;
    private java.util.List<String> selectedCellIds;
    private Long selectionCanvasVersion;
    private String selectionContentHash;
    private String canvasImageDataUrl;
    private String canvasImageRendererVersion;
    private CanvasSnapshotDTO canvasSnapshot;
    private ClientHintsDTO clientHints;

    // 最近可见聊天轮次；只供意图路由补全上下文，不作为画布编辑内容。
    private java.util.List<DiagramConversationMessageDTO> conversationMessages;

    // 已保存的模型凭证；chat 只接受凭证 ID，不接受请求体里的原始 API key。
    private String modelCredentialId;

    // 旧版自定义配置字段只用于拒绝兼容期 raw-key 请求，不能再作为模型调用来源。
    private String customBaseUrl;
    private String customApiKey;
    private String customCompletionsPath;
    private String customModel;

    // 用户可调的确定性修复轮数；VLM 自动修复使用独立的服务端固定预算。
    private Integer maxDeterministicRepairRounds;

    // 兼容旧客户端一个发布周期；新代码不再发送该字段。
    private Integer maxReviewIterations;

    // 用户手动指定要使用的技能(名),覆盖意图路由的自动选择;可多选(组合)。为空则走自动选择。
    private java.util.List<String> skills;

    @Data
    public static class DirectClarificationDTO {
        private String reasonCode;
        private String resolution;
        private String observedFingerprint;
    }

    @Data
    public static class CanvasSnapshotDTO {
        private Boolean valid;
        private Integer nodeCount;
        private Integer edgeCount;
        private BoundsDTO bounds;
        private java.util.List<String> labels;
    }

    @Data
    public static class BoundsDTO {
        private Double x;
        private Double y;
        private Double width;
        private Double height;
    }

    @Data
    public static class ClientHintsDTO {
        private Integer maxDeterministicRepairRounds;
        private Integer maxReviewIterations;
        private java.util.List<String> skills;
    }

}
