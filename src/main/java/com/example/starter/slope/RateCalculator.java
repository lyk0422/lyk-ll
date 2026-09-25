package com.example.starter.slope;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 速率曲线复算器：由按监测时刻升序排列的累计位移读数推导小时速率与日速率。
 * 纯函数、无副作用，入库与查询均通过它复算，保证速率曲线可复算。
 */
public final class RateCalculator {

    private RateCalculator() {
    }

    /** 一条位移读数（输入）。 */
    public record Sample(LocalDateTime measuredAt, double displacementMm) {
    }

    /** 复算结果：hourlyRate/dailyRate 可能为 null（无基准点）。 */
    public record Rated(LocalDateTime measuredAt, double displacementMm,
                        Double hourlyRate, Double dailyRate) {
    }

    /**
     * 复算速率曲线。
     *
     * @param ordered 按监测时刻升序排列的读数
     * @return 与输入等长、同序的复算结果
     */
    public static List<Rated> compute(List<Sample> ordered) {
        List<Rated> result = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Sample current = ordered.get(i);
            Double hourly = null;
            if (i > 0) {
                Sample prev = ordered.get(i - 1);
                double hours = Duration.between(prev.measuredAt(), current.measuredAt()).toMinutes() / 60.0;
                if (hours > 0) {
                    hourly = (current.displacementMm() - prev.displacementMm()) / hours;
                }
            }
            Double daily = null;
            LocalDateTime dayAgo = current.measuredAt().minusHours(24);
            Sample reference = null;
            for (int j = i - 1; j >= 0; j--) {
                Sample candidate = ordered.get(j);
                if (!candidate.measuredAt().isAfter(dayAgo)) {
                    reference = candidate;
                    break;
                }
            }
            if (reference != null) {
                double days = Duration.between(reference.measuredAt(), current.measuredAt()).toMinutes() / (60.0 * 24.0);
                if (days > 0) {
                    daily = (current.displacementMm() - reference.displacementMm()) / days;
                }
            }
            result.add(new Rated(current.measuredAt(), current.displacementMm(), hourly, daily));
        }
        return result;
    }
}
