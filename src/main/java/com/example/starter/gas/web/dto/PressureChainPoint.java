package com.example.starter.gas.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 压力链复算单点：依据落库压力按 [下限,上限]（含边界）重新判定联动动作。
 */
public record PressureChainPoint(
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime detectedAt,
        BigDecimal pressureKpa,
        String pressureAction,
        boolean regulated
) {
}
