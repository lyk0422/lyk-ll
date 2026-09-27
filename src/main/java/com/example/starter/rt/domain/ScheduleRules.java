package com.example.starter.rt.domain;

import java.util.Map;

/**
 * 合成复杂度等级规则（仅合成，不依据真实临床规则）：
 * 等级 1/2/3 对应固定设备时长 10/20/30 分钟。
 */
public final class ScheduleRules {

    /** 复杂度等级 → 固定所需分钟数。 */
    private static final Map<Integer, Integer> REQUIRED_MINUTES = Map.of(1, 10, 2, 20, 3, 30);

    /** 单次批量排程最多连续分次条数。 */
    public static final int MAX_BATCH_FRACTIONS = 10;

    private ScheduleRules() {
    }

    /** 返回等级对应固定分钟数（1→10，2→20，3→30）。 */
    public static int requiredMinutes(int complexityLevel) {
        Integer minutes = REQUIRED_MINUTES.get(complexityLevel);
        if (minutes == null) {
            throw new IllegalArgumentException("非法复杂度等级：" + complexityLevel);
        }
        return minutes;
    }
}
