package com.example.starter.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 取消申请请求：仅 RESERVED 可取消。
 */
public record CancelRequest(
        @NotNull(message = "不能为空") Integer expectedVersion) {
}
