package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 投料请求：携带全部染化料实际称量；偏差超 ±0.5% 整单拒绝（恰 0.5% 合法）。
 * expectedVersion 仅受控缸次必填：为该缸补偿批准的乐观锁版本（FROZEN→FED 成功变更加 1）；
 * 旧非受控缸次不传，保持原投料语义。
 */
public record FeedRequest(
        @NotBlank String requestId,
        @NotBlank String lotNo,
        Integer expectedVersion,
        @NotEmpty @Valid List<WeighingInput> weighings) {
}
