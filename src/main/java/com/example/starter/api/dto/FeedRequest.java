package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 投料请求：携带全部染化料实际称量；偏差超 ±0.5% 整单拒绝（恰 0.5% 合法）。
 */
public record FeedRequest(
        @NotBlank String requestId,
        @NotBlank String lotNo,
        @NotEmpty @Valid List<WeighingInput> weighings) {
}
