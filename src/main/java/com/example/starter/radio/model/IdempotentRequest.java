package com.example.starter.radio.model;

/**
 * 新增写操作的幂等冻结记录。resultSnapshot 为首次成功结果的 JSON 快照；
 * 同键同参重放直接返回该快照，业务状态随后变化不影响重放结果。
 */
public record IdempotentRequest(
        String requestId,
        String fingerprint,
        String operation,
        String resultSnapshot,
        long createdAtEpoch) {
}
