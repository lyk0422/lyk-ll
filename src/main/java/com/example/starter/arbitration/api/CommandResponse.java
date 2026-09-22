package com.example.starter.arbitration.api;

/**
 * 指令提交结果。非法/过期/回拨指令返回 4xx 且不入队。
 *
 * @param accepted    是否进入执行队列
 * @param commandId   服务端指令主键
 * @param controllerId 发送控制器
 * @param requestId   幂等键
 * @param resourceName 目标资源
 * @param status      队列状态（PENDING/EXECUTED）
 * @param enqueuedAtMs 入队时间（UTC epoch 毫秒）
 * @param idempotentReplay 是否为同参重复提交的幂等回放
 */
public record CommandResponse(
        boolean accepted,
        Long commandId,
        String controllerId,
        String requestId,
        String resourceName,
        String status,
        long enqueuedAtMs,
        boolean idempotentReplay) {
}
