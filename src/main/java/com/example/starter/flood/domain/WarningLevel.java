package com.example.starter.flood.domain;

/**
 * 预警级别：泄流能力不足时自动逐级提升，RED 封顶。
 */
public enum WarningLevel {
    BLUE,
    YELLOW,
    ORANGE,
    RED;

    /**
     * 提升一级；已为 RED 时维持 RED。
     */
    public WarningLevel escalate() {
        int next = Math.min(ordinal() + 1, values().length - 1);
        return values()[next];
    }

    /**
     * 按名称解析，未知级别按 ORANGE 处理（兼容历史数据）。
     */
    public static WarningLevel of(String name) {
        if (name == null) {
            return ORANGE;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return ORANGE;
        }
    }
}
