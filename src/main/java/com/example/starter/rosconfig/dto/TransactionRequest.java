package com.example.starter.rosconfig.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 跨节点事务式热更新请求。
 *
 * @param transactionId 调用方生成的幂等键；同键同参返回原结果，同键异参返回 409
 * @param changes       各节点变更集，至少包含一个节点
 */
public record TransactionRequest(
        @NotBlank String transactionId,
        @NotEmpty List<@Valid ChangeSetRequest> changes
) {
}
