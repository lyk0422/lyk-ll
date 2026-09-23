package com.example.starter.rights.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 排期提交请求：整笔校验，任一频段无完整授权覆盖则全部零写入。
 */
public record SubmitScheduleRequest(
        @NotBlank String requestId,
        @NotEmpty @Valid List<ScheduleSegment> segments) {
}
