package com.example.starter.rolling.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 物理辊代次链视图：同时展示代次链与各代独立台账，
 * 旧辊号的里程、实绩、禁排及冻结结论全部保留可查。
 *
 * @param physicalId       合成物理辊唯一编号
 * @param currentRollNo    当前代次辊号
 * @param currentGenerationNo 当前代次号
 * @param repairCount      累计成功返修次数（上限 2）
 * @param version          聚合乐观锁版本（从 1 起，每次成功返修加 1）
 * @param generations      各代独立台账，按代次号升序
 */
public record RollGenerationChainView(
        Long physicalId,
        String currentRollNo,
        Integer currentGenerationNo,
        Integer repairCount,
        Integer version,
        List<GenerationLedgerView> generations) {

    /**
     * 单代独立台账。
     *
     * @param generationNo    代次号（从 1 起）
     * @param rollNo          该代辊号（首代辊号沿用登记号，后续代为返修新号）
     * @param status          状态：IN_SERVICE 在役 / BANNED 到期禁排
     * @param totalKm         该代累计实绩里程（公里）；新代从 0 起独立累加
     * @param cycleLimitKm    该代周期上限（公里）
     * @param remainingKm     该代剩余里程（公里）
     * @param diameterMm      该代磨后直径（毫米）；首代为 null
     * @param coilsSinceMount 该代本次上机后已轧卷数（前 5 卷首用烫辊位）
     * @param frozenAt        该代日冻结时刻（UTC）；null 表示未冻结
     * @param lateEvidenceCount 返修建代后回传到该旧辊号的迟到实绩证据条数
     */
    public record GenerationLedgerView(
            Integer generationNo,
            String rollNo,
            String status,
            BigDecimal totalKm,
            BigDecimal cycleLimitKm,
            BigDecimal remainingKm,
            BigDecimal diameterMm,
            Integer coilsSinceMount,
            LocalDateTime mountedAt,
            LocalDateTime frozenAt,
            Long lateEvidenceCount) {
    }
}
