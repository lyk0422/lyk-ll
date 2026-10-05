package com.example.starter.flood.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调洪服务参数：签收时限与回执偏差口径均可通过配置覆盖，便于测试边界。
 */
@ConfigurationProperties(prefix = "flood-control")
public class FloodControlProperties {

    /** 指令签收时限（分钟），下达后恰满该时长仍未签收则升级值班领导。 */
    private int signTimeoutMinutes = 15;

    /** 回执实际泄量与方案泄量允许的最大偏差比例（含边界），默认 0.10。 */
    private double receiptDeviationRatio = 0.10;

    /** 滚动演算周期（分钟）：距上一版本恰满该时长才触发滚动重算，默认 30。 */
    private int rollingIntervalMinutes = 30;

    public int getSignTimeoutMinutes() {
        return signTimeoutMinutes;
    }

    public void setSignTimeoutMinutes(int signTimeoutMinutes) {
        this.signTimeoutMinutes = signTimeoutMinutes;
    }

    public double getReceiptDeviationRatio() {
        return receiptDeviationRatio;
    }

    public void setReceiptDeviationRatio(double receiptDeviationRatio) {
        this.receiptDeviationRatio = receiptDeviationRatio;
    }

    public int getRollingIntervalMinutes() {
        return rollingIntervalMinutes;
    }

    public void setRollingIntervalMinutes(int rollingIntervalMinutes) {
        this.rollingIntervalMinutes = rollingIntervalMinutes;
    }
}
