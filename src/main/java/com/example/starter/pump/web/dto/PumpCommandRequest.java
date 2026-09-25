package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 泵站启停指令。
 *
 * @param requestId   幂等键
 * @param pumpdownId  所属预降事件ID
 * @param stationCode 泵站代码
 * @param action      动作 START 启泵 / STOP 停泵
 * @param taskTime    启停时刻（Asia/Shanghai）
 * @param gateOpening 启泵联动闸门开度（百分比）；仅 START 时使用，为空取站点配置开度
 */
public record PumpCommandRequest(
        @NotBlank String requestId,
        @NotNull Long pumpdownId,
        @NotBlank String stationCode,
        @NotBlank String action,
        @NotNull LocalDateTime taskTime,
        BigDecimal gateOpening
) {
}
