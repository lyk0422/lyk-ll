package com.example.starter.rt.dto;

/**
 * 排程取消结果。
 *
 * @param scheduleId      排程ID
 * @param status          取消后状态 CANCELLED
 * @param version         取消后的排程版本（2）
 * @param requestId       取消幂等键
 * @param replayed        是否为同键同参重放
 */
public record ScheduleCancelResponse(
        Long scheduleId,
        String status,
        Integer version,
        String requestId,
        boolean replayed) {
}
