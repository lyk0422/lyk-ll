package com.example.starter.media.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 排期提交请求：全部时段要么整笔绑定成功，要么零写入。
 */
public record SubmitScheduleRequest(
        @NotBlank String requestId,
        @NotBlank String scheduleId,
        @NotEmpty @Valid List<ScheduleSlotDto> slots
) {
}
