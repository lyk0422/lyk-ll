package com.example.starter.wb.dto;

import java.time.LocalDateTime;

/**
 * 装配图逐项对外视图：含装载时舱位整数毫米力臂与皮/净/总重。
 */
public record AssemblyItemView(
        String uldNo,
        String uldType,
        String positionCode,
        int armMm,
        int tareKg,
        int netKg,
        int grossKg
) {
}
