package org.zipp.ai.types.enums;

import lombok.Getter;

@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    NOT_FOUND_METHOD("0003", "不存在的方法"),
    AUTH_FORBIDDEN("AUTH_FORBIDDEN", "Admin access required."),
    AUTH_RATE_LIMITED("AUTH_RATE_LIMITED", "Too many attempts. Please try again later."),
    DEMO_QUOTA_EXHAUSTED("DEMO_QUOTA_EXHAUSTED", "Demo quota exhausted. Sign up or add your own API key to continue."),
    PLATFORM_QUOTA_EXHAUSTED("PLATFORM_QUOTA_EXHAUSTED", "Daily free AI quota exhausted. Use your own API key or try again tomorrow."),
    CANVAS_VERSION_CONFLICT("CANVAS_VERSION_CONFLICT", "Canvas state version conflict. Refresh the diagram and retry."),
    INVALID_CANVAS_XML("INVALID_CANVAS_XML", "Canvas XML is missing or exceeds the size limit."),

    E0001("E0001", "智能体ID不存在"),
    E0002("E0002", "智能体MCP配置不在可加载范围");

    private String code;
    private String info;

    ResponseCode(String code, String info) {
        this.code = code;
        this.info = info;
    }
}
