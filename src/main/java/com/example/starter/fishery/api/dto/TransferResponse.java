package com.example.starter.fishery.api.dto;

import com.example.starter.fishery.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 跨船配额调剂单结果/视图。
 *
 * @param id 调剂单号
 * @param fromVesselCode 调出渔船编号
 * @param toVesselCode 调入渔船编号
 * @param speciesCode 鱼种编号
 * @param quotaYear 配额年度（UTC）
 * @param amountKg 调剂量（千克）
 * @param status 状态：PENDING/EFFECTIVE/EXPIRED
 * @param createdAt 发起时刻（UTC）
 * @param expiresAt 确认截止时刻（UTC，发起后 48 小时，恰满即失效）
 * @param confirmedAt 对方确认生效时刻（UTC），未确认为空
 * @param replayed 是否幂等重放
 */
public record TransferResponse(
        Long id,
        String fromVesselCode,
        String toVesselCode,
        String speciesCode,
        int quotaYear,
        BigDecimal amountKg,
        TransferStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant confirmedAt,
        boolean replayed) implements ReplayAware {

    @Override
    public TransferResponse withReplayed(boolean replayed) {
        return new TransferResponse(id, fromVesselCode, toVesselCode, speciesCode, quotaYear,
                amountKg, status, createdAt, expiresAt, confirmedAt, replayed);
    }
}
