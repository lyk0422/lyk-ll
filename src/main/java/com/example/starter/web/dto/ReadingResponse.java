package com.example.starter.web.dto;

import com.example.starter.domain.TemperatureReading;

import java.math.BigDecimal;

/**
 * 温度读数视图。
 */
public record ReadingResponse(
        long sequenceNo,
        long sampledAt,
        BigDecimal temperatureC,
        boolean replayed,
        long createdAt
) {

    /**
     * @param reading  读数
     * @param replayed 是否为同序号同参重放（未产生新写入）
     */
    public static ReadingResponse from(TemperatureReading reading, boolean replayed) {
        return new ReadingResponse(
                reading.sequenceNo(),
                reading.sampledAt(),
                reading.temperatureC(),
                replayed,
                reading.createdAt()
        );
    }
}
