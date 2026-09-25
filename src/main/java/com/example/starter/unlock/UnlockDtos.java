package com.example.starter.unlock;

import jakarta.validation.constraints.NotBlank;

/**
 * 紧急解锁接口报文定义。
 */
public final class UnlockDtos {

    private UnlockDtos() {
    }

    /** 紧急解锁请求：须双人授权。reason 由数据库非空约束兜底，用于验证事务原子性。 */
    public record UnlockRequest(
            @NotBlank String deviceId,
            @NotBlank String ruleCode,
            @NotBlank String authorizerA,
            @NotBlank String authorizerB,
            String reason) {
    }

    /**
     * 解锁结果。
     *
     * @param unlockRecordId  解锁记录主键
     * @param restoreRecordId 闭锁恢复记录主键
     * @param status          固定 RESTORED：解锁与闭锁恢复已在同一事务完成
     */
    public record UnlockResponse(long unlockRecordId, long restoreRecordId, String status) {
    }
}
