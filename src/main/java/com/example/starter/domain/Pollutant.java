package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 参与小时均值评价的大气污染物（合成监测点口径），限值单位 mg/m3。
 */
public enum Pollutant {

    NOX(BigDecimal.valueOf(400)),
    SO2(BigDecimal.valueOf(200)),
    PARTICULATE(BigDecimal.valueOf(30));

    private final BigDecimal defaultLimitMgM3;

    Pollutant(BigDecimal defaultLimitMgM3) {
        this.defaultLimitMgM3 = defaultLimitMgM3;
    }

    /**
     * @return 配置缺省时的标准限值，单位 mg/m3
     */
    public BigDecimal defaultLimitMgM3() {
        return defaultLimitMgM3;
    }
}
