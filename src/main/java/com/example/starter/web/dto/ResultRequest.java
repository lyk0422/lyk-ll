package com.example.starter.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 检测结果上报请求；取消后到达返回 409 且不写入。
 *
 * @param result 任意 JSON 可序列化结果载荷
 */
public record ResultRequest(
        @NotNull(message = "不能为空") Integer expectedVersion,
        @NotNull(message = "不能为空") Object result) {
}
