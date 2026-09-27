package com.example.starter.rt.dto;

/**
 * 排程批次取消结果。
 *
 * @param scheduleId     排程批次ID
 * @param status         取消后批次状态 CANCELLED
 * @param cancelledCount 本次释放占用的未开始项条数
 * @param version        取消后批次版本号
 * @param requestId      取消幂等键
 * @param replayed       是否为同键同参重放
 */
public record ScheduleCancelResponse(
        Long scheduleId,
        String status,
        Integer cancelledCount,
        Integer version,
        String requestId,
        boolean replayed) {
}
