package com.example.starter.ros;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

/**
 * 事务式热更新请求。
 *
 * @param transactionId 业务幂等键；同键同参返回原结果，同键异参返回 409
 * @param nodes         目标节点 ID -> 该节点的期望版本与变更集
 */
public record TransactionRequest(
        @NotBlank String transactionId,
        @NotEmpty Map<String, NodeChange> nodes) {
}
