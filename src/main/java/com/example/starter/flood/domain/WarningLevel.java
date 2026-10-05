package com.example.starter.flood.domain;

/**
 * 预警级别：BLUE &lt; YELLOW &lt; ORANGE &lt; RED。
 * 滚动演算判定泄流能力不足时自动逐级提升；已为 RED 时保持并留痕。
 */
public enum WarningLevel {
    /** 蓝色（最低）。 */
    BLUE,
    /** 黄色。 */
    YELLOW,
    /** 橙色（合成数据默认级别）。 */
    ORANGE,
    /** 红色（最高，不可再升）。 */
    RED;

    /**
     * 提升一级；已为最高级时返回自身。
     */
    public WarningLevel escalate() {
        return switch (this) {
            case BLUE -> YELLOW;
            case YELLOW -> ORANGE;
            case ORANGE -> RED;
            case RED -> RED;
        };
    }
}
