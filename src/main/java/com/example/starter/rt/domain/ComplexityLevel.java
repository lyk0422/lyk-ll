package com.example.starter.rt.domain;

/**
 * 合成复杂度等级：等级 1/2/3 固定对应单分次所需 10/20/30 分钟。
 * 仅为合成排程规则，不依据真实临床规则。
 */
public enum ComplexityLevel {

    /** 等级 1：10 分钟。 */
    L1(1, 10),
    /** 等级 2：20 分钟。 */
    L2(2, 20),
    /** 等级 3：30 分钟。 */
    L3(3, 30);

    private final int level;
    private final int minutes;

    ComplexityLevel(int level, int minutes) {
        this.level = level;
        this.minutes = minutes;
    }

    public int level() {
        return level;
    }

    public int minutes() {
        return minutes;
    }

    /** 等级是否合法（1 至 3）。 */
    public static boolean isValid(int level) {
        return level >= L1.level && level <= L3.level;
    }

    /**
     * 等级对应的单分次所需分钟数。
     *
     * @throws IllegalArgumentException 等级不在 1 至 3
     */
    public static int minutesOf(int level) {
        for (ComplexityLevel value : values()) {
            if (value.level == level) {
                return value.minutes;
            }
        }
        throw new IllegalArgumentException("非法复杂度等级：" + level);
    }
}
