package com.example.starter.support;

/**
 * 潜水硬性规则常量与纯函数计算。
 *
 * <p>速率：下潜/出水速率不得超过 18m/min，恰 18 合法；
 * 时长：同一潜水员 24 小时滚动窗口（UTC）累计水下时间不超过 6 小时，恰 6 小时合法；
 * 观察：出水后观察 2 小时，恰满 2 小时且无症状方可结案。
 */
public final class DiveRules {

    /** 下潜与出水速率上限，单位 m/min；恰等于该值合法。 */
    public static final double MAX_RATE_M_PER_MIN = 18.0d;

    /** 24 小时滚动窗口，单位秒。 */
    public static final long ACCRUAL_WINDOW_SECONDS = 24L * 60 * 60;

    /** 24 小时内累计水下时长上限，单位秒；恰等于该值合法（6 小时）。 */
    public static final long MAX_UNDERWATER_SECONDS_PER_24H = 6L * 60 * 60;

    /** 出水后观察时长，单位秒；恰满该时长方可结案（2 小时）。 */
    public static final long OBSERVATION_SECONDS = 2L * 60 * 60;

    private DiveRules() {
    }
}
