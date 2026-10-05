package com.example.starter.flood.web.dto;

import java.time.LocalDateTime;

/**
 * 替代建议视图：在途指令与新方案版本冲突时生成，人工确认留痕可查。
 */
public record AdviceView(
        String adviceNo,
        String orderNo,
        Integer orderPlanVersion,
        String newPlanNo,
        Integer newPlanVersion,
        String suggestion,
        String status,
        String decision,
        String confirmNote,
        String confirmedBy,
        LocalDateTime createdAt,
        LocalDateTime confirmedAt,
        boolean replay) {
}
