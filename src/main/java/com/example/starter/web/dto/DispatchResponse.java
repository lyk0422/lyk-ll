package com.example.starter.web.dto;

/**
 * 派单响应。
 *
 * @param workOrderId 工单ID
 * @param teamId      班组ID
 * @param result      DISPATCHED已派单 / QUEUED挂单
 * @param status      工单当前状态
 * @param replayed    是否为同键同参幂等重放
 */
public record DispatchResponse(
        long workOrderId,
        long teamId,
        String result,
        String status,
        boolean replayed) {
}
