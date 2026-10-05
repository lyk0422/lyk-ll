package com.example.starter.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 水泥窑掺烧业务合成参数。比例为小数（0.40 表示 40%），质量 kg，浓度 mg/Nm3。
 */
@ConfigurationProperties(prefix = "cement")
public class CementProperties {

    /** 替代燃料热量替代率上限，恰等于合法。 */
    private BigDecimal maxSubstitutionRate = new BigDecimal("0.40");
    /** 降掺保底比例，强制降掺的目标比例。 */
    private BigDecimal floorSubstitutionRate = new BigDecimal("0.10");
    /** 入窑氯总量限值 kg（高氯硫燃料质量加权），恰等于合法。 */
    private BigDecimal chlorineLimitKg = new BigDecimal("800.0");
    /** 入窑硫总量限值 kg，恰等于合法。 */
    private BigDecimal sulfurLimitKg = new BigDecimal("15000.0");
    /** 降掺指令下发后确认时限（分钟），恰满未确认即升级。 */
    private long commandConfirmTimeoutMinutes = 30;
    /** 连续小时均值超标触发当日禁烧的次数，恰等于触发。 */
    private int consecutiveExceedBan = 3;
    /** 保温段超时升级告警时长（小时），恰满即升级。 */
    private long warmupHoldingTimeoutHours = 4;
    /** 各污染物小时均值排放限值 mg/Nm3，恰等于判达标。 */
    private Map<String, BigDecimal> emissionLimits = new LinkedHashMap<>();

    public BigDecimal getMaxSubstitutionRate() {
        return maxSubstitutionRate;
    }

    public void setMaxSubstitutionRate(BigDecimal maxSubstitutionRate) {
        this.maxSubstitutionRate = maxSubstitutionRate;
    }

    public BigDecimal getFloorSubstitutionRate() {
        return floorSubstitutionRate;
    }

    public void setFloorSubstitutionRate(BigDecimal floorSubstitutionRate) {
        this.floorSubstitutionRate = floorSubstitutionRate;
    }

    public BigDecimal getChlorineLimitKg() {
        return chlorineLimitKg;
    }

    public void setChlorineLimitKg(BigDecimal chlorineLimitKg) {
        this.chlorineLimitKg = chlorineLimitKg;
    }

    public BigDecimal getSulfurLimitKg() {
        return sulfurLimitKg;
    }

    public void setSulfurLimitKg(BigDecimal sulfurLimitKg) {
        this.sulfurLimitKg = sulfurLimitKg;
    }

    public long getCommandConfirmTimeoutMinutes() {
        return commandConfirmTimeoutMinutes;
    }

    public void setCommandConfirmTimeoutMinutes(long commandConfirmTimeoutMinutes) {
        this.commandConfirmTimeoutMinutes = commandConfirmTimeoutMinutes;
    }

    public int getConsecutiveExceedBan() {
        return consecutiveExceedBan;
    }

    public void setConsecutiveExceedBan(int consecutiveExceedBan) {
        this.consecutiveExceedBan = consecutiveExceedBan;
    }

    public long getWarmupHoldingTimeoutHours() {
        return warmupHoldingTimeoutHours;
    }

    public void setWarmupHoldingTimeoutHours(long warmupHoldingTimeoutHours) {
        this.warmupHoldingTimeoutHours = warmupHoldingTimeoutHours;
    }

    public Map<String, BigDecimal> getEmissionLimits() {
        return emissionLimits;
    }

    public void setEmissionLimits(Map<String, BigDecimal> emissionLimits) {
        this.emissionLimits = emissionLimits;
    }
}
