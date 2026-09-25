package com.example.starter.dye.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 投料执行请求。称量明细须覆盖配方全部染化料；任一偏差 |实际-计划|/计划 &gt; 0.5% 即整体拒绝，
 * 恰 0.5% 合法；拒绝不扣减库存。
 */
public record FeedRequest(
        @NotBlank String requestId,
        @NotBlank String runNo,
        @NotEmpty @Valid List<FeedItem> items) {
}
