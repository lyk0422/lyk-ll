package com.example.starter.fermentation.dto;

import java.time.Instant;

/**
 * 降温指令受理结果：切换前后曲线版本，二者与指令在同一事务内生效。
 */
public record CoolingResponse(
        String requestId,
        String batchNo,
        Instant commandAt,
        Integer fromVersion,
        Integer toVersion,
        String batchStatus) {
}
