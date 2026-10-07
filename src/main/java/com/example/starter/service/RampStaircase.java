package com.example.starter.service;

import java.util.ArrayList;
import java.util.List;

import com.example.starter.web.ApiUnprocessableException;

/**
 * 受控分段阶梯生成（纯逻辑，可独立单测）。
 * 规则：从起始功率每分钟向目标移动，单分钟移动量不超过升降上限（正整数 kW/min），
 * 到达目标后保持目标值；期限内无法恰达目标则整体不可行（调用方整单拒绝）。
 */
public final class RampStaircase {

    private RampStaircase() {
    }

    /** 期限内是否可恰达目标：|目标−起始| ≤ 每分钟上限 × 期限分钟数。 */
    public static boolean reachable(int startKw, int targetKw, int rampRateKwPerMin, int durationMinutes) {
        long distance = Math.abs((long) targetKw - startKw);
        return distance <= (long) rampRateKwPerMin * durationMinutes;
    }

    /**
     * 生成 1..durationMinutes 的完整阶梯（含恰达目标后的保持段），最后一分钟恰为目标。
     *
     * @throws ApiUnprocessableException 期限内无法到达目标
     */
    public static List<Integer> build(int startKw, int targetKw, int rampRateKwPerMin, int durationMinutes) {
        if (!reachable(startKw, targetKw, rampRateKwPerMin, durationMinutes)) {
            throw new ApiUnprocessableException(
                    "起始 " + startKw + " kW 以每分钟 " + rampRateKwPerMin + " kW 升降，"
                            + durationMinutes + " 分钟内无法到达目标 " + targetKw + " kW");
        }
        List<Integer> steps = new ArrayList<>(durationMinutes);
        int current = startKw;
        for (int minute = 1; minute <= durationMinutes; minute++) {
            int diff = targetKw - current;
            int move = Math.max(-rampRateKwPerMin, Math.min(rampRateKwPerMin, diff));
            current += move;
            steps.add(current);
        }
        return steps;
    }
}
