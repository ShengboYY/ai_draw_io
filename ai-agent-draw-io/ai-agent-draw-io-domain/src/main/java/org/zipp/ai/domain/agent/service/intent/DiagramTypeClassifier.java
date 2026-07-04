package org.zipp.ai.domain.agent.service.intent;

import java.util.Locale;
import java.util.regex.Pattern;

public class DiagramTypeClassifier {

    private static final Rule[] STRUCTURED_RULES = {
            new Rule("flowchart", "流程", "审批", "业务流", "flowchart", "process"),
            new Rule("architecture", "架构", "系统", "部署", "微服务", "architecture"),
            new Rule("uml", "类图", "uml", "class"),
            new Rule("sequence", "时序", "调用链", "交互", "sequence"),
            new Rule("er", "数据库", "数据表", "表", "schema", "entity", "er"),
            new Rule("usecase", "用例", "参与者", "actor", "use case", "usecase"),
            new Rule("state", "状态", "生命周期", "state"),
            new Rule("mindmap", "思维导图", "脑图", "mindmap", "概念图")
    };

    private static final String[] BLANK_KEYWORDS = {"空画布", "空白", "blank canvas", "blank"};
    private static final String[] ILLUSTRATION_KEYWORDS = {
            "插画", "cute", "draw a", "draw an", "画只", "画一只",
            "猫", "狗", "动物", "头像", "物体",
            "cat", "dog", "animal", "pet", "bird", "fish", "car", "tree", "flower"
    };

    public String classify(String userInstruction) {
        String text = normalize(userInstruction);
        if (text.isEmpty()) {
            return "others";
        }
        if (containsAny(text, BLANK_KEYWORDS)) {
            return "blank";
        }
        for (Rule rule : STRUCTURED_RULES) {
            if (containsAny(text, rule.keywords())) {
                return rule.diagramType();
            }
        }
        // Freeform drawings are checked after structural diagram terms so "draw a flowchart" stays flowchart.
        if (containsAny(text, ILLUSTRATION_KEYWORDS)) {
            return "illustration";
        }
        return "others";
    }

    private String normalize(String value) {
        return null == value ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (containsKeyword(text, keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsKeyword(String text, String keyword) {
        if (isAsciiWordKeyword(keyword)) {
            return Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(keyword) + "([^a-z0-9]|$)")
                    .matcher(text)
                    .find();
        }
        return text.contains(keyword);
    }

    private boolean isAsciiWordKeyword(String keyword) {
        return keyword.chars().allMatch(ch -> ch < 128 && (Character.isLetterOrDigit(ch) || Character.isWhitespace(ch)));
    }

    private record Rule(String diagramType, String... keywords) {
    }

}
