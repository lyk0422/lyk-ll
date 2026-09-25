package com.example.starter.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 台账业务阈值配置（SOC、健康度、调拨超时）。
 */
@ConfigurationProperties(prefix = "battery.ledger")
public class LedgerProperties {

    /** 上架可换所需最低 SOC，百分比，恰好达标视为合格。 */
    private int socThreshold = 90;

    /** 上架可换所需最低健康度，百分比，恰好达标视为合格。 */
    private int healthThreshold = 80;

    /** 调拨在途超时小时数（UTC），超过自动转调拨异常。 */
    private long transferTimeoutHours = 24;

    public int getSocThreshold() {
        return socThreshold;
    }

    public void setSocThreshold(int socThreshold) {
        this.socThreshold = socThreshold;
    }

    public int getHealthThreshold() {
        return healthThreshold;
    }

    public void setHealthThreshold(int healthThreshold) {
        this.healthThreshold = healthThreshold;
    }

    public long getTransferTimeoutHours() {
        return transferTimeoutHours;
    }

    public void setTransferTimeoutHours(long transferTimeoutHours) {
        this.transferTimeoutHours = transferTimeoutHours;
    }
}
