package com.example.starter.ros.domain;

/**
 * 节点可声明的 ROS 2 资源类别。
 * 仅 {@link #SERVICE} 与 {@link #ACTION} 在存活节点间同名互斥；{@link #TOPIC} 允许多节点共享。
 */
public enum ResourceCategory {
    TOPIC,
    SERVICE,
    ACTION;

    /**
     * 解析资源类别字符串，大小写不敏感。
     *
     * @param raw 原始值
     * @return 枚举值
     * @throws IllegalArgumentException 未知类别
     */
    public static ResourceCategory from(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("resource category must not be null");
        }
        return ResourceCategory.valueOf(raw.trim().toUpperCase());
    }

    /**
     * 该类别是否在存活节点范围内同名互斥。
     *
     * @return Service/Action 返回 true
     */
    public boolean isExclusive() {
        return this == SERVICE || this == ACTION;
    }
}
