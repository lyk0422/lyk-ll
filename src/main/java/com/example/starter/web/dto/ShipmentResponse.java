package com.example.starter.web.dto;

import com.example.starter.domain.Shipment;

import java.math.BigDecimal;

/**
 * 货件视图。
 */
public record ShipmentResponse(
        String shipmentId,
        BigDecimal minTempC,
        BigDecimal maxTempC,
        int maxExcursionMinutes,
        int maxSampleIntervalMinutes,
        int version,
        long createdAt
) {

    /**
     * 由领域对象构造视图。
     */
    public static ShipmentResponse from(Shipment s) {
        return new ShipmentResponse(
                s.shipmentId(),
                s.minTempC(),
                s.maxTempC(),
                s.maxExcursionMinutes(),
                s.maxSampleIntervalMinutes(),
                s.version(),
                s.createdAt()
        );
    }
}
