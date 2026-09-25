package com.example.starter.metro;

import org.springframework.stereotype.Component;

/**
 * 票价计算器：起步价 2 元含 4 公里，超出部分每 5 公里加 1 元，
 * 不足 5 公里按 5 公里计，恰满 5 公里按加价档计。
 */
@Component
public class FareCalculator {

    /** 起步价（分）。 */
    public static final long BASE_FARE_CENTS = 200L;
    /** 起步价包含里程（米）。 */
    public static final long BASE_DISTANCE_M = 4000L;
    /** 加价档里程步长（米）。 */
    public static final long STEP_DISTANCE_M = 5000L;
    /** 每档加价（分）。 */
    public static final long STEP_FARE_CENTS = 100L;

    /**
     * 按里程计算票价。
     *
     * @param distanceM 里程（米），0 表示尚未发生行程，票价为 0
     * @return 票价（分）
     */
    public long fareCents(long distanceM) {
        if (distanceM <= 0) {
            return 0L;
        }
        long extra = distanceM - BASE_DISTANCE_M;
        if (extra <= 0) {
            return BASE_FARE_CENTS;
        }
        long steps = (extra + STEP_DISTANCE_M - 1) / STEP_DISTANCE_M;
        return BASE_FARE_CENTS + steps * STEP_FARE_CENTS;
    }
}
