package com.example.starter.rt.domain;

/**
 * 合成复杂度等级：等级 1/2/3 固定对应每分次 10/20/30 分钟设备占用时长。
 * 仅为合成排程规则，不依据真实临床规则。
 */
public final class ComplexityLevel {

    /** 最低等级。 */
    public static final int MIN = 1;
    /** 最高等级。 */
    public static final int MAX = 3;

    private ComplexityLevel() {
    }

    /** 等级是否合法（1-3）。 */
    public static boolean isValid(int level) {
        return level >= MIN && level <= MAX;
    }

    /** 等级对应的每分次占用分钟数：1→10，2→20，3→30。 */
    public static int minutesOf(int level) {
        if (!isValid(level)) {
            throw new IllegalArgumentException("非法复杂度等级：" + level);
        }
        return level * 10;
    }
}
