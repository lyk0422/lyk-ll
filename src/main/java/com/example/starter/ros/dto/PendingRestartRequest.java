package com.example.starter.ros.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 创建待重启配置请求：静态参数变更只能落为待重启，重启前不生效。
 *
 * @param transactionId 可选来源事务 ID；缺省由服务端生成
 * @param parameters    参数组 -> 参数名 -> 值（仅允许静态参数）
 */
public record PendingRestartRequest(
        String transactionId,
        @NotBlank String node,
        @NotNull Map<@NotBlank String, Map<@NotBlank String, Object>> parameters
) {
}
