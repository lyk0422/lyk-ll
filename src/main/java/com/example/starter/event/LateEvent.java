package com.example.starter.event;

import java.time.LocalDateTime;

/**
 * 迟到事件：批次结案（终态）后迟到的检测/复检结果只记事件，不改变批次状态。时间均为 UTC。
 */
public record LateEvent(
        long id,
        long batchId,
        String eventType,
        String detail,
        LocalDateTime createdAt) {
}
