package com.example.starter.specimen.web.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * 仅携带乐观锁版本的请求体，用于开始、取消等状态迁移。
 */
public record VersionedRequest(@PositiveOrZero long expectedVersion) {
}
