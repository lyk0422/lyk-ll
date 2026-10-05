package com.example.starter.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** 各污染物小时均值排放限值 mg/Nm3，恰等于判达标。 */
    private Map<String, BigDecimal> emissionLimits = new LinkedHashMap<>();
    /** 冷修后升温合成曲线与保温超时参数。 */
    private WarmUp warmUp = new WarmUp();

    /**
     * 升温曲线阶段定义：目标温度(℃)与最长用时(分钟)，恰等于目标判达标、恰满用时转保温。
     */
    public static class CurveStage {

        /** 曲线内阶段序号，从 1 开始连续递增。 */
        private int stageNo;
        /** 阶段目标温度，单位摄氏度。 */
        private BigDecimal targetTemp;
        /** 阶段最长用时，单位分钟。 */
        private long maxDurationMinutes;

        public int getStageNo() {
            return stageNo;
        }

        public void setStageNo(int stageNo) {
            this.stageNo = stageNo;
        }

        public BigDecimal getTargetTemp() {
            return targetTemp;
        }

        public void setTargetTemp(BigDecimal targetTemp) {
            this.targetTemp = targetTemp;
        }

        public long getMaxDurationMinutes() {
            return maxDurationMinutes;
        }

        public void setMaxDurationMinutes(long maxDurationMinutes) {
            this.maxDurationMinutes = maxDurationMinutes;
        }
    }

    /**
     * 冷修后升温合成曲线参数。
     */
    public static class WarmUp {

        /** 合成曲线版本号，随升温票与阶段记录落库。 */
        private String curveVersion = "CURVE-2026A";
        /** 保温段超时升级告警阈值（小时），恰满即判定。 */
        private long holdTimeoutHours = 4;
        /** 合成升温曲线阶段列表，按 stageNo 升序执行。 */
        private List<CurveStage> stages = new ArrayList<>();

        public String getCurveVersion() {
            return curveVersion;
        }

        public void setCurveVersion(String curveVersion) {
            this.curveVersion = curveVersion;
        }

        public long getHoldTimeoutHours() {
            return holdTimeoutHours;
        }

        public void setHoldTimeoutHours(long holdTimeoutHours) {
            this.holdTimeoutHours = holdTimeoutHours;
        }

        public List<CurveStage> getStages() {
            return stages;
        }

        public void setStages(List<CurveStage> stages) {
            this.stages = stages;
        }
    }

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

    public Map<String, BigDecimal> getEmissionLimits() {
        return emissionLimits;
    }

    public void setEmissionLimits(Map<String, BigDecimal> emissionLimits) {
        this.emissionLimits = emissionLimits;
    }

    public WarmUp getWarmUp() {
        return warmUp;
    }

    public void setWarmUp(WarmUp warmUp) {
        this.warmUp = warmUp;
    }
}
