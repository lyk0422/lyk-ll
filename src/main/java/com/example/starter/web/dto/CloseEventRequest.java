package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 事件结案请求。
 */
public record CloseEventRequest(
        @NotBlank String closedBy) {
}
