package org.zipp.ai.application.memory;

import java.util.Locale;
import java.util.regex.Pattern;

/** Skips only clearly non-durable turns before the model-backed extraction boundary. */
public final class AutoMemoryExtractionEligibilityPolicy {
    private static final Pattern DURABLE_SIGNAL = Pattern.compile(
            "\\b(?:future|always|usually|prefer|preference|like|dislike|love|hate|every|all projects?|all chartbooks?|"
                    + "default|from now on|do not .* again|don't .* again)\\b"
                    + "|(?:以后|今后|總是|总是|通常|偏好|喜欢|喜歡|默认|默認|每次|"
                    + "不喜欢|不喜歡|讨厌|討厭|所有项目|所有項目|所有画册|所有畫冊|不要再|別再|别再)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SOCIAL_ONLY = Pattern.compile(
            "^(?:hi|hello|hey|thanks|thank you|ok|okay|got it|你好|您好|嗨|谢谢|謝謝|"
                    + "好的|好|可以|收到|明白|知道了|沒問題|没问题|已了解)[\\p{P}\\s]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MEMORY_READ_ONLY = Pattern.compile(
            "^(?:please\\s+)?(?:tell|show|list)\\s+(?:me\\s+)?(?:what you remember|my memories)"
                    + "[\\p{P}\\s]*$"
                    + "|^(?:请|請)?(?:告诉|告訴|显示|顯示|列出|查看)(?:我)?"
                    + "(?:你)?(?:记住|記住|保存)(?:了)?(?:什么|什麼|哪些|的.*(?:规则|規則|偏好|记忆|記憶))"
                    + "[\\p{P}\\s]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ONE_OFF_CREATION = Pattern.compile(
            "^(?:please\\s+)?(?:draw|create|generate|make)\\b"
                    + "|^(?:(?:请|請)\\s*)?(?:(?:帮|幫)我\\s*)?"
                    + "(?:画|畫|绘制|繪製|创建|創建|生成)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LOCAL_TARGET = Pattern.compile(
            "\\b(?:this|current|selected)\\b|(?:这个|這個|这张|這張|当前|當前|选中|選中|刚才|剛才)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MUTATION = Pattern.compile(
            "\\b(?:add|move|delete|remove|rename|resize|replace|change|edit|draw)\\b"
                    + "|(?:添加|增加|移动|移動|删除|刪除|移除|改名|重命名|缩放|縮放|替换|替換|修改|绘制|繪製|画|畫)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public boolean shouldExtract(String content) {
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            return false;
        }
        // A durable marker wins over one-off wording so mixed requests are never silently dropped.
        if (DURABLE_SIGNAL.matcher(normalized).find()) {
            return true;
        }
        if (SOCIAL_ONLY.matcher(normalized).matches()
                || MEMORY_READ_ONLY.matcher(normalized).matches()
                || ONE_OFF_CREATION.matcher(normalized).find()) {
            return false;
        }
        return !(LOCAL_TARGET.matcher(normalized).find()
                && MUTATION.matcher(normalized).find());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
