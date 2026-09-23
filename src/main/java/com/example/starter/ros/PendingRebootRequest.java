package com.example.starter.ros;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.Map;

/**
 * 静态参数待重启配置请求。
 *
 * @param transactionId 幂等键；同键同参返回原结果，同键异参返回 409
 * @param values        待重启后生效的静态参数新值
 */
public record PendingRebootRequest(
        @NotBlank String transactionId,
        @NotEmpty Map<String, Object> values) {

    /**
     * 待重启配置创建结果。
     *
     * @param transactionId 事务 ID
     * @param nodeId        节点 ID
     * @param pending       已登记、待重启后应用的参数（参数名 -> 新值文本）
     * @param applied       恒为 false：待重启配置绝非“已应用”
     * @param replayed      是否幂等重放
     */
    public record Response(String transactionId,
                           String nodeId,
                           Map<String, String> pending,
                           boolean applied,
                           boolean replayed,
                           Instant createdAt) {
    }
}
