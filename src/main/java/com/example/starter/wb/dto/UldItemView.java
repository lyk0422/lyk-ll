package com.example.starter.wb.dto;

/**
 * 装配图逐项对外视图：力臂为整数毫米，重量为整数公斤。
 */
public record UldItemView(
        int seqNo,
        String uldNo,
        String compartmentCode,
        int armMm,
        int tareKg,
        int netKg,
        int grossKg
) {
}
