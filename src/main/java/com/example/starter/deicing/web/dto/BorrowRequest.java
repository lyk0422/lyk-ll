package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 跨班组借用请求体。requestId 幂等：同键同参返回首次结果，同键异参 409。
 *
 * @param requestId 幂等键
 * @param tankId    液罐编号，同一液罐同时刻仅允许一张未归还借用单
 * @param fluidCode 除冰液编码
 * @param quantity  借出量（升）
 * @param fromTeam  借出班组
 * @param toTeam    借用班组
 */
public record BorrowRequest(
        @NotBlank String requestId,
        @NotBlank String tankId,
        @NotBlank String fluidCode,
        @Min(1) int quantity,
        @NotBlank String fromTeam,
        @NotBlank String toTeam) {
}
