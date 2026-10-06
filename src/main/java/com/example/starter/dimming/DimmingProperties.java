package com.example.starter.dimming;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调光业务参数，单位见 application.yaml 注释。
 */
@ConfigurationProperties(prefix = "dimming")
public class DimmingProperties {

    /** 最低档位（关闭） */
    private int minLevel = 0;
    /** 最高档位（全亮） */
    private int maxLevel = 5;
    /** 保底亮度档位，车流量恰为0时降至该档 */
    private int floorLevel = 1;
    /** 事故模式锁定档位 */
    private int accidentLevel = 5;
    /** 洞外亮度合成阈值（lux），低于立即提升一档，恰等不触发 */
    private double brightnessThresholdLux = 500.0;
    /** 稳定时限（分钟），单次调整后须稳定该时长方可再调，恰满允许 */
    private long stabilityMinutes = 10;
    /** 过渡曲线时长（分钟），过渡期间禁止下调 */
    private long transitionMinutes = 2;
    /** 车流量滚动统计窗口（分钟） */
    private long trafficWindowMinutes = 5;
    /** 每档位功率（kW），用于日结能耗复算 */
    private double powerPerLevelKw = 0.1;

    public int getMinLevel() {
        return minLevel;
    }

    public void setMinLevel(int minLevel) {
        this.minLevel = minLevel;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public void setMaxLevel(int maxLevel) {
        this.maxLevel = maxLevel;
    }

    public int getFloorLevel() {
        return floorLevel;
    }

    public void setFloorLevel(int floorLevel) {
        this.floorLevel = floorLevel;
    }

    public int getAccidentLevel() {
        return accidentLevel;
    }

    public void setAccidentLevel(int accidentLevel) {
        this.accidentLevel = accidentLevel;
    }

    public double getBrightnessThresholdLux() {
        return brightnessThresholdLux;
    }

    public void setBrightnessThresholdLux(double brightnessThresholdLux) {
        this.brightnessThresholdLux = brightnessThresholdLux;
    }

    public long getStabilityMinutes() {
        return stabilityMinutes;
    }

    public void setStabilityMinutes(long stabilityMinutes) {
        this.stabilityMinutes = stabilityMinutes;
    }

    public long getTransitionMinutes() {
        return transitionMinutes;
    }

    public void setTransitionMinutes(long transitionMinutes) {
        this.transitionMinutes = transitionMinutes;
    }

    public long getTrafficWindowMinutes() {
        return trafficWindowMinutes;
    }

    public void setTrafficWindowMinutes(long trafficWindowMinutes) {
        this.trafficWindowMinutes = trafficWindowMinutes;
    }

    public double getPowerPerLevelKw() {
        return powerPerLevelKw;
    }

    public void setPowerPerLevelKw(double powerPerLevelKw) {
        this.powerPerLevelKw = powerPerLevelKw;
    }
}
