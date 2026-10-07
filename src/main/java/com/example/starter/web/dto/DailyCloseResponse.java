package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日结勾稽与封存结果。
 *
 * @param bizDate           业务日期
 * @param status            SEALED 封存终态 / MANUAL 转人工
 * @param totalFlowM3       当日有效检测累计流量 m³
 * @param expectedOdorantMg 理论消耗 mg = 累计流量 × 20
 * @param reportedOdorantMg 实际上报消耗 mg
 * @param deviationPct      勾稽偏差百分比（恰 3% 合格封存）
 * @param sealed            是否已封存（终态）
 * @param sealedAt          封存时刻
 * @param idempotentReplay  是否为同键同参重放
 * @param transferInKg      当日调拨转入 kg（台账实时复算展示，不改旧封存快照）
 * @param transferOutKg     当日调拨转出 kg（台账实时复算展示，不改旧封存快照）
 */
public record DailyCloseResponse(
        LocalDate bizDate,
        String status,
        BigDecimal totalFlowM3,
        BigDecimal expectedOdorantMg,
        BigDecimal reportedOdorantMg,
        BigDecimal deviationPct,
        boolean sealed,
        LocalDateTime sealedAt,
        boolean idempotentReplay,
        BigDecimal transferInKg,
        BigDecimal transferOutKg) implements Replayable<DailyCloseResponse> {

    /**
     * 兼容旧调用：无调拨转入/转出展示（按 0 处理）。
     */
    public DailyCloseResponse(LocalDate bizDate, String status, BigDecimal totalFlowM3,
                              BigDecimal expectedOdorantMg, BigDecimal reportedOdorantMg,
                              BigDecimal deviationPct, boolean sealed, LocalDateTime sealedAt,
                              boolean idempotentReplay) {
        this(bizDate, status, totalFlowM3, expectedOdorantMg, reportedOdorantMg, deviationPct,
                sealed, sealedAt, idempotentReplay, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Override
    public DailyCloseResponse withReplay(boolean replay) {
        return new DailyCloseResponse(bizDate, status, totalFlowM3, expectedOdorantMg,
                reportedOdorantMg, deviationPct, sealed, sealedAt, replay,
                transferInKg, transferOutKg);
    }
}
