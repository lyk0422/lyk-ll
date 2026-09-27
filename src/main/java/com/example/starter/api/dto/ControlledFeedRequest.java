package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 受控投料请求：引用补偿批准，按冻结的补偿目标校验称量偏差（恰 ±0.5% 合法），原子扣减批次余量。
 */
public record ControlledFeedRequest(
        @NotBlank String requestId,
        @NotBlank String lotNo,
        @NotBlank String approvalNo,
        @NotEmpty @Valid List<WeighingInput> weighings) {
}
