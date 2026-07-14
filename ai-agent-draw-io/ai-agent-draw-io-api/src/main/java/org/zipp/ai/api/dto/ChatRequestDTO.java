package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class ChatRequestDTO {

    private String agentId;
    private String userId;
    private String sessionId;
    private String requestId;
    // Server-owned correlation id; controllers overwrite any client-provided value before use.
    private String runId;
    private String diagramId;
    private Long expectedVersion;
    private String message;
    private String canvasXml;
    private String canvasSummary;
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

    // Draw.io 审查修订循环次数，由前端控制，后端会做上限保护。
    private Integer maxReviewIterations;

    // 用户手动指定要使用的技能(名),覆盖意图路由的自动选择;可多选(组合)。为空则走自动选择。
    private java.util.List<String> skills;

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
        private Integer maxReviewIterations;
        private java.util.List<String> skills;
    }

}
