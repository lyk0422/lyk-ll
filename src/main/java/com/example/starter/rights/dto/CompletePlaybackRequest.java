package com.example.starter.rights.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 播出完成回执请求；播出时间缺省取排期绑定的时段时间。
 */
public record CompletePlaybackRequest(
        @NotBlank String requestId,
        Instant airStart,
        Instant airEnd) {
}
