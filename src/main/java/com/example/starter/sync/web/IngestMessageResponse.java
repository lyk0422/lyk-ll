package com.example.starter.sync.web;

import com.example.starter.sync.domain.IngestOutcome;

/**
 * 消息接收响应。status 为 ACCEPTED 或 DISCARDED；丢弃时 reason 给出稳定原因码。
 */
public record IngestMessageResponse(
        String status,
        String sensorType,
        String messageId,
        long rawTs,
        long correctedTs,
        int driftVersion,
        String reason) {

    /**
     * 从领域结果转换。
     */
    public static IngestMessageResponse of(IngestOutcome outcome) {
        return new IngestMessageResponse(
                outcome.status().name(),
                outcome.sample().sensorType().name(),
                outcome.sample().messageId(),
                outcome.sample().rawTs(),
                outcome.sample().correctedTs(),
                outcome.sample().driftVersion(),
                outcome.reason() == null ? null : outcome.reason().name());
    }
}
