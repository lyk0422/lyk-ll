package com.example.starter.dto;

import java.time.Instant;

/**
 * 调光操作结果（同时作为幂等登记表中的首次结果快照）。
 *
 * @param requestId 幂等键
 * @param circuitId 回路主键
 * @param source    触发来源
 * @param fromLevel 调光前档位，未触发为 null
 * @param toLevel   调光后档位，未触发为 null
 * @param status    结果状态：EXECUTED 已执行 / NOT_TRIGGERED 未触发
 * @param message   结果说明
 * @param createdAt 结果生成时间(UTC)
 * @param replayed  是否幂等重放（同键同参命中首次结果）
 */
public record DimResult(
        String requestId,
        long circuitId,
        String source,
        Integer fromLevel,
        Integer toLevel,
        String status,
        String message,
        Instant createdAt,
        boolean replayed) {

    /** 已执行。 */
    public static final String STATUS_EXECUTED = "EXECUTED";
    /** 未触发（条件不满足或目标档位与当前一致）。 */
    public static final String STATUS_NOT_TRIGGERED = "NOT_TRIGGERED";

    public DimResult withReplayed(boolean value) {
        return new DimResult(requestId, circuitId, source, fromLevel, toLevel, status, message, createdAt, value);
    }
}
