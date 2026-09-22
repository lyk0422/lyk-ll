package com.example.starter.ros.domain;

/**
 * 节点资源类别。SERVICE 与 ACTION 的全限定名称在所有存活节点间互斥;
 * TOPIC 允许多个节点共存同名声明。
 */
public enum ResourceCategory {
    TOPIC,
    SERVICE,
    ACTION;

    /**
     * 解析资源类别字符串,大小写不敏感;非法值返回 null 由调用方按 400 处理。
     */
    public static ResourceCategory fromString(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase()) {
            case "TOPIC" -> TOPIC;
            case "SERVICE" -> SERVICE;
            case "ACTION" -> ACTION;
            default -> null;
        };
    }

    /**
     * 该类别名称是否需要全局互斥(SERVICE/ACTION)。
     */
    public boolean isExclusive() {
        return this == SERVICE || this == ACTION;
    }
}
