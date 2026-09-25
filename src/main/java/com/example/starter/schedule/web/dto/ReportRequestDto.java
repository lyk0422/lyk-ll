package com.example.starter.schedule.web.dto;

import com.example.starter.schedule.model.ReportStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 执行回报入参，按“窗口＋站”去重。
 */
public record ReportRequestDto(
        @NotNull Long windowId,
        @NotNull ReportStatus status,
        String remark) {
}
