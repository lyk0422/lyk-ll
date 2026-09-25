package com.example.starter.waterlogging.po;

import java.math.BigDecimal;

/**
 * 预警级别表 warning_level 映射。
 */
public record WarningLevel(
        Long id,
        String levelCode,
        String levelName,
        Integer sortNo,
        Boolean triggerPreDrain) {
}
