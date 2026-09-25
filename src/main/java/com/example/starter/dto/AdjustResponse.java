package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 调节令响应。
 *
 * @param requestId  请求幂等键
 * @param taskId     调节任务ID
 * @param taskCreated 本次是否新建任务（首条调节令为 true）
 * @param steps      各步进执行结果
 * @param adjustedAt 调节时刻(UTC)
 * @param replay     是否为同键同参重放返回
 */
public record AdjustResponse(
        String requestId,
        Long taskId,
        boolean taskCreated,
        List<StepResult> steps,
        Instant adjustedAt,
        boolean replay
) {
}
