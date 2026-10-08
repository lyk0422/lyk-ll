package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 检修单取消响应。
 *
 * @param id          检修单 id
 * @param version     取消后的版本（加 1）
 * @param status      检修单状态（CANCELLED）
 * @param cancelledAt 取消时间（UTC）
 */
public record MaintenanceCancelResponse(long id, int version, String status, Instant cancelledAt) {
}
