package com.example.starter.greenhouse.service;

import com.example.starter.greenhouse.po.ClimateStrategy;
import com.example.starter.greenhouse.po.SensorReading;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * 设定带判定：设定带为 [目标值-容差, 目标值+容差]，含边界算达成/不偏离。
 * 昼/夜以 UTC 小时划分：06:00（含）~18:00（不含）为白天，其余为夜间。
 */
@Component
public class ClimateBandEvaluator {

    /** 温度或湿度落在对应昼/夜设定带内（含边界）。 */
    public boolean isWithinBand(ClimateStrategy s, Instant sampleTime,
                                BigDecimal temperature, BigDecimal humidity) {
        boolean daytime = isDaytime(sampleTime);
        BigDecimal tempTarget = daytime ? s.getDayTempTarget() : s.getNightTempTarget();
        BigDecimal tempTol = daytime ? s.getDayTempTolerance() : s.getNightTempTolerance();
        BigDecimal humTarget = daytime ? s.getDayHumidityTarget() : s.getNightHumidityTarget();
        BigDecimal humTol = daytime ? s.getDayHumidityTolerance() : s.getNightHumidityTolerance();
        return inBand(temperature, tempTarget, tempTol) && inBand(humidity, humTarget, humTol);
    }

    public boolean isWithinBand(ClimateStrategy s, SensorReading r) {
        return isWithinBand(s, r.getSampleTime(), r.getTemperature(), r.getHumidity());
    }

    public boolean isDaytime(Instant sampleTime) {
        int hour = sampleTime.atZone(ZoneOffset.UTC).getHour();
        return hour >= 6 && hour < 18;
    }

    private boolean inBand(BigDecimal value, BigDecimal target, BigDecimal tolerance) {
        return value.compareTo(target.subtract(tolerance)) >= 0
                && value.compareTo(target.add(tolerance)) <= 0;
    }
}
