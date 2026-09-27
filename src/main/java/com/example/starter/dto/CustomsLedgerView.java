package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 通关单余量台账视图：通关单登记信息 + 当前预占/核销/余量 + 各子批绑定明细。
 *
 * @param customsNo           合成通关单号
 * @param enterpriseName      登记企业
 * @param productName         登记品种
 * @param validUntil          有效截止精确时刻
 * @param totalQuantity       可核销总支数（单位：支）
 * @param reservedQuantity    当前已预占未核销支数（单位：支）
 * @param writtenOffQuantity  累计已核销支数（单位：支）
 * @param availableQuantity   未占余量 = 总支数 - 已预占 - 已核销（单位：支）
 * @param status              通关单状态 ACTIVE/REVOKED
 * @param version             当前版本号（从 1 开始）
 * @param revokedAt           撤销时刻，空值表示未撤销
 * @param bindings            关联包装子批绑定明细
 */
public record CustomsLedgerView(
        String customsNo,
        String enterpriseName,
        String productName,
        Instant validUntil,
        int totalQuantity,
        int reservedQuantity,
        int writtenOffQuantity,
        int availableQuantity,
        String status,
        int version,
        Instant revokedAt,
        List<BindingView> bindings
) {
}
