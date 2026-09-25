package com.example.starter.radio.model;

/**
 * 批次完成后的迟到事件，仅记录不改动业务状态。
 */
public record LateEvent(
        long id,
        long dispensingId,
        String eventType,
        String payload,
        long receivedAtEpoch) {
}
