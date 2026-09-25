package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 恢复开放请求：恢复开放与航行通告发布在同一事务内完成。
 *
 * @param requestId 幂等请求号
 */
public record ReopenRequest(@NotBlank String requestId) {
}
