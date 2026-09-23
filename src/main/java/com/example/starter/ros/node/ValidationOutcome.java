package com.example.starter.ros.node;

/**
 * 节点对候选配置的校验结论。
 *
 * @param accepted 是否接受
 * @param reason   拒绝原因（接受时为 null）
 */
public record ValidationOutcome(boolean accepted, String reason) {
    public static ValidationOutcome accept() {
        return new ValidationOutcome(true, null);
    }

    public static ValidationOutcome reject(String reason) {
        return new ValidationOutcome(false, reason);
    }
}
