package com.example.starter.web.dto;

/**
 * 日结封存响应。
 *
 * @param workOrderId 工单ID
 * @param status      工单状态（SEALED）
 * @param replayed    是否为重复封存的幂等成功
 */
public record SealResponse(long workOrderId, String status, boolean replayed) {
}
