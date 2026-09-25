package com.example.starter.epidemic.api;

/**
 * 审核/撤回/归档等状态动作的返回结果。
 *
 * @param idempotentHit 是否为幂等重放（如对已归档卡重复归档返回成功）
 */
public record ActionResult(
        String cardNumber,
        String status,
        int currentVersion,
        boolean idempotentHit,
        String message
) {
}
