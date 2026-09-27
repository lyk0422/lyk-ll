package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 切换候选确认查询结果：候选状态、固定闸门目标与已收回执。
 *
 * @param candidateId   切换候选ID
 * @param zoneCode      流域分区代码
 * @param direction     切换方向 ENTER_FORCED/EXIT_FORCED
 * @param tideTime      触发潮位时刻（UTC）
 * @param tideLevel     触发潮位（米）
 * @param pinnedVersion 生成时固定的分区工况版本
 * @param status        PENDING/CONFIRMED/SUPERSEDED
 * @param targets       固定的各站闸门目标
 * @param receipts      已收到的闸门到位回执
 * @param createdAt     候选生成时刻
 */
public record CandidateDetailResult(
        Long candidateId,
        String zoneCode,
        String direction,
        LocalDateTime tideTime,
        BigDecimal tideLevel,
        int pinnedVersion,
        String status,
        List<GateTargetItem> targets,
        List<GateReceiptItem> receipts,
        LocalDateTime createdAt
) {

    /** 固定闸门目标单条。 */
    public record GateTargetItem(String stationCode, BigDecimal targetOpening) {
    }

    /** 闸门到位回执单条。 */
    public record GateReceiptItem(String stationCode, BigDecimal opening, LocalDateTime receivedAt) {
    }
}
