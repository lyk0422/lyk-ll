package com.example.starter.rt.domain;

/**
 * 合成复杂度等级：设备可执行等级 1 至 3，每分次所需分钟数固定为 10/20/30。
 * 仅为合成排程规则，不依据真实临床规则。
 */
public final class ComplexityLevel {

    /** 最低等级。 */
    public static final int MIN = 1;
    /** 最高等级。 */
    public static final int MAX = 3;
    /** 每等级对应分钟数（等级1=10分钟，等级2=20分钟，等级3=30分钟）。 */
    public static final int MINUTES_PER_LEVEL = 10;

    private ComplexityLevel() {
    }

    /** 等级是否合法（1至3）。 */
    public static boolean isValid(int level) {
        return level >= MIN && level <= MAX;
    }

    /** 等级对应的每分次所需分钟数：10/20/30。 */
    public static int requiredMinutes(int level) {
        if (!isValid(level)) {
            throw new IllegalArgumentException("非法复杂度等级：" + level);
        }
        return level * MINUTES_PER_LEVEL;
    }
}
