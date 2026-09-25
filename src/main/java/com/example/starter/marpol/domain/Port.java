package com.example.starter.marpol.domain;

import java.math.BigDecimal;

/**
 * 港口/岸线合成参考点（表 port），用于计算“距最近陆地”海里数。
 */
public class Port {

    private String portCode;
    private String portName;
    private BigDecimal latitude;
    private BigDecimal longitude;

    public String getPortCode() {
        return portCode;
    }

    public void setPortCode(String portCode) {
        this.portCode = portCode;
    }

    public String getPortName() {
        return portName;
    }

    public void setPortName(String portName) {
        this.portName = portName;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }
}
