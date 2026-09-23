package com.example.starter.media.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 播出完成回执请求：以服务端可控时刻标记某个时段已完成播出。
 */
public record PlayReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String scheduleId,
        @NotNull Integer itemNo
) {
}
