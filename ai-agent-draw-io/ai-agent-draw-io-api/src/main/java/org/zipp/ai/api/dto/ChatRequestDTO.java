package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class ChatRequestDTO {

    private String agentId;
    private String userId;
    private String sessionId;
    private String message;

    // 自定义配置
    private String customBaseUrl;
    private String customApiKey;
    private String customCompletionsPath;
    private String customModel;

    // Draw.io 审查修订循环次数，由前端控制，后端会做上限保护。
    private Integer maxReviewIterations;

}
