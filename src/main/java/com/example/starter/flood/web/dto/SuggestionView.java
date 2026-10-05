package com.example.starter.flood.web.dto;

import java.time.LocalDateTime;

/**
 * 在途指令冲突替代建议视图（人工确认留痕可查）。
 */
public record SuggestionView(
        String suggestionNo,
        String orderNo,
        String oldPlanNo,
        String newPlanNo,
        Integer oldVersionNo,
        Integer newVersionNo,
        String suggestionDetail,
        String status,
        String decision,
        String confirmedBy,
        LocalDateTime confirmedAt,
        String confirmNote,
        boolean replay,
        LocalDateTime createdAt) {
}
