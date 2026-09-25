package com.example.starter.quarantine.api.dto;

import com.example.starter.quarantine.domain.RequestStatus;

/**
 * 检疫单据全流程视图，申报/出证/启运接口返回。
 *
 * @param requestStatus 单据状态
 * @param certificate   已出证时返回证视图，否则为 null
 * @param landedQuantity 已接受的累计落地数量，单位 头/只
 * @param quarantineFlag 本单是否被强制隔离观察
 * @param rejectReason  驳回/作废原因
 * @param idempotentReplay 是否为同键同配重放返回的首次结果
 */
public record CaseView(
        String requestId,
        String originFarm,
        String destination,
        String animalType,
        String earTagRange,
        int quantity,
        RequestStatus requestStatus,
        CertificateView certificate,
        int landedQuantity,
        boolean quarantineFlag,
        String rejectReason,
        boolean idempotentReplay) {
}
