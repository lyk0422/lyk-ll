package com.example.starter.dto;

import com.example.starter.model.ReinspectionResult;
import java.time.LocalDateTime;

/**
 * 复检登记响应：包含复检后维修单状态与限功率状态。
 */
public record ReinspectionResponse(
        long id,
        long repairOrderId,
        ReinspectionResult result,
        LocalDateTime inspectedAt,
        String orderStatus,
        boolean powerLimitActive) {
}
