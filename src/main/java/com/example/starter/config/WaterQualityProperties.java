package com.example.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 水质监测业务参数；单位与限值含义见各字段注释，恰等限值判定为正常。
 */
@ConfigurationProperties(prefix = "water")
public class WaterQualityProperties {

    /**
     * 各指标限值。
     */
    private Thresholds thresholds = new Thresholds();

    /**
     * 连续越限采样点升级紧急状态的点数，恰达到该点数即触发。
     */
    private int consecutiveViolationLimit = 3;

    /**
     * 处置指令回执确认时限（分钟），恰满该时长未确认即升级。
     */
    private int confirmTimeoutMinutes = 5;

    /**
     * 传感器失联判定时长（分钟），恰满该时长无心跳即标记数据缺失。
     */
    private int sensorLostMinutes = 10;

    public Thresholds getThresholds() {
        return thresholds;
    }

    public void setThresholds(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    public int getConsecutiveViolationLimit() {
        return consecutiveViolationLimit;
    }

    public void setConsecutiveViolationLimit(int consecutiveViolationLimit) {
        this.consecutiveViolationLimit = consecutiveViolationLimit;
    }

    public int getConfirmTimeoutMinutes() {
        return confirmTimeoutMinutes;
    }

    public void setConfirmTimeoutMinutes(int confirmTimeoutMinutes) {
        this.confirmTimeoutMinutes = confirmTimeoutMinutes;
    }

    public int getSensorLostMinutes() {
        return sensorLostMinutes;
    }

    public void setSensorLostMinutes(int sensorLostMinutes) {
        this.sensorLostMinutes = sensorLostMinutes;
    }

    /**
     * 水质指标限值；越限判定为严格不等式，恰等限值为正常。
     */
    public static class Thresholds {

        /**
         * 溶解氧下限（mg/L），低于该值越限。
         */
        private double dissolvedOxygenMin = 5.0;

        /**
         * 氨氮上限（mg/L），高于该值越限。
         */
        private double ammoniaNitrogenMax = 0.5;

        /**
         * 温度下限（摄氏度），低于该值越限。
         */
        private double temperatureMin = 18.0;

        /**
         * 温度上限（摄氏度），高于该值越限。
         */
        private double temperatureMax = 28.0;

        public double getDissolvedOxygenMin() {
            return dissolvedOxygenMin;
        }

        public void setDissolvedOxygenMin(double dissolvedOxygenMin) {
            this.dissolvedOxygenMin = dissolvedOxygenMin;
        }

        public double getAmmoniaNitrogenMax() {
            return ammoniaNitrogenMax;
        }

        public void setAmmoniaNitrogenMax(double ammoniaNitrogenMax) {
            this.ammoniaNitrogenMax = ammoniaNitrogenMax;
        }

        public double getTemperatureMin() {
            return temperatureMin;
        }

        public void setTemperatureMin(double temperatureMin) {
            this.temperatureMin = temperatureMin;
        }

        public double getTemperatureMax() {
            return temperatureMax;
        }

        public void setTemperatureMax(double temperatureMax) {
            this.temperatureMax = temperatureMax;
        }
    }
}
