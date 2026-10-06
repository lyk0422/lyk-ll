package com.example.starter.model;

import java.time.LocalDateTime;

/**
 * 缺陷记录行：巡检按叶片与位置登记，detectedAt 为首次缺陷登记时刻(UTC)。
 */
public record DefectRow(
        long id,
        long bladeId,
        String bladePosition,
        DefectLevel defectLevel,
        String defectType,
        LocalDateTime detectedAt) {
}
