package com.example.starter.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 掺烧业务合成限值配置（cement.* 前缀）。
 *
 * @param chlorineLimitKgH      入窑氯总量限值，单位 kg/h
 * @param sulfurLimitKgH        入窑硫总量限值，单位 kg/h
 * @param maxHeatRatio          替代燃料热量替代率上限（含边界，恰 40% 合法）
 * @param reductionStep         单次降掺步长（热量替代率绝对降幅）
 * @param confirmDeadlineMinutes 降掺指令下发后的确认时限，单位分钟
 * @param consecutiveExceedanceBan 连续多少个小时均值超标触发当日禁烧
 * @param emissionLimits        各污染物小时均值标准限值，单位 mg/m3，恰等于限值判达标
 */
@ConfigurationProperties(prefix = "cement")
public record CementProperties(
        BigDecimal chlorineLimitKgH,
        BigDecimal sulfurLimitKgH,
        BigDecimal maxHeatRatio,
        BigDecimal reductionStep,
        long confirmDeadlineMinutes,
        int consecutiveExceedanceBan,
        Map<String, BigDecimal> emissionLimits) {

    public CementProperties {
        if (chlorineLimitKgH == null) {
            chlorineLimitKgH = new BigDecimal("0.6000");
        }
        if (sulfurLimitKgH == null) {
            sulfurLimitKgH = new BigDecimal("12.0000");
        }
        if (maxHeatRatio == null) {
            maxHeatRatio = new BigDecimal("0.4000");
        }
        if (reductionStep == null) {
            reductionStep = new BigDecimal("0.1000");
        }
        if (confirmDeadlineMinutes == 0L) {
            confirmDeadlineMinutes = 30L;
        }
        if (consecutiveExceedanceBan == 0) {
            consecutiveExceedanceBan = 3;
        }
        if (emissionLimits == null) {
            emissionLimits = new LinkedHashMap<>();
        }
    }
}
