package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class ChatRequestDTO {

    private String agentId;
    private String userId;
    private String sessionId;
    private String diagramId;
    private Long expectedVersion;
    private String message;
    private String canvasXml;
    private String canvasSummary;
    private CanvasSnapshotDTO canvasSnapshot;
    private ClientHintsDTO clientHints;

    // 自定义配置
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
