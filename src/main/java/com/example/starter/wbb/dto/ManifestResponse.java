package com.example.starter.wbb.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 舱单查询/操作响应：含重量平衡计算结果、版本链信息、复核状态与业载明细，可复算。
 */
public record ManifestResponse(
        Long id,
        String flightKey,
        String flightNo,
        LocalDate flightDate,
        String aircraftType,
        String aircraftRegistration,
        Integer versionNo,
        String status,
        Long parentId,
        String requestId,
        BigDecimal fuelKg,
        BigDecimal fuelArm,
        Integer passengerCount,
        BigDecimal payloadKg,
        BigDecimal zeroFuelWeightKg,
        BigDecimal zfwCgArm,
        BigDecimal takeoffWeightKg,
        BigDecimal towCgArm,
        Integer lmcDeltaPax,
        BigDecimal lmcDeltaKg,
        Boolean frozen,
        Boolean envelopeOk,
        Boolean payloadLimitOk,
        Boolean wboSigned,
        Boolean dspSigned,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        List<PayloadItemDto> items
) {
}
