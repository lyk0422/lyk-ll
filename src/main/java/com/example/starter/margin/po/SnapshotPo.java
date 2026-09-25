package com.example.starter.margin.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 账户资产负债已确认快照持久化对象。比例判定一律取账户最新一条 confirmed 快照。
 *
 * @param id               自增主键
 * @param accountNo        账户号
 * @param totalAssets      总资产（含现金与担保证券市值），单位元
 * @param totalLiabilities 总负债，单位元
 * @param confirmed        是否已确认；仅已确认快照参与判定
 * @param marketTime       行情/账务时点（UTC）
 * @param remark           备注（快照来源）
 * @param createdAt        入库时间（UTC）
 */
public record SnapshotPo(
        Long id,
        String accountNo,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        boolean confirmed,
        Instant marketTime,
        String remark,
        Instant createdAt
) {
}
