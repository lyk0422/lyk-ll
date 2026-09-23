package com.example.starter.costmap.domain;

import java.util.Locale;

/**
 * 障碍物来源类型。
 *
 * <p>多来源同格合并的固定、确定性优先级：{@link #SAFETY} &gt; {@link #MANUAL} &gt;
 * {@link #SENSOR} &gt; {@link #GENERIC}。priority 数值越小优先级越高。</p>
 */
public enum SourceType {
    /** 安全系统（急停、触边等），最高优先级。 */
    SAFETY(0),
    /** 人工标注/干预。 */
    MANUAL(1),
    /** 传感器实时感知。 */
    SENSOR(2),
    /** 其他通用来源，最低优先级。 */
    GENERIC(3);

    private final int priority;

    SourceType(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    /**
     * 按名称（大小写不敏感）解析来源类型，无法识别时抛出 IllegalArgumentException。
     */
    public static SourceType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        return SourceType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
