package com.example.starter.dto;

import com.example.starter.model.DefectLevel;
import java.time.LocalDateTime;

/**
 * 缺陷登记响应：包含登记后派生的限功率与根因评估状态。
 */
public record DefectResponse(
        long id,
        long bladeId,
        String bladePosition,
        DefectLevel defectLevel,
        String defectType,
        LocalDateTime detectedAt,
        boolean powerLimitActive,
        boolean assessmentTriggered,
        Long assessmentId) {
}
