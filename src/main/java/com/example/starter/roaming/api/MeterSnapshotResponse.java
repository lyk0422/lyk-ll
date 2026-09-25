package com.example.starter.roaming.api;

import java.math.BigDecimal;

/**
 * 计量快照上传结果；重复快照不重计电量，乱序按抄表序号归位。
 *
 * @param sessionNo       会话号
 * @param readingSeq      抄表序号
 * @param energyKwh       该序号电表累计读数（kWh）
 * @param deduplicated    是否为重复快照（true 表示未重复计费）
 * @param totalEnergyKwh  归位后会话最新累计电量（kWh）
 * @param suspended       是否形成差异挂账（封存后迟到）
 * @param suspenseNo      挂账单号（未挂账为空）
 * @param replayed        是否为 requestId 幂等重放
 */
public record MeterSnapshotResponse(
        String sessionNo,
        Integer readingSeq,
        BigDecimal energyKwh,
        boolean deduplicated,
        BigDecimal totalEnergyKwh,
        boolean suspended,
        String suspenseNo,
        boolean replayed) {
}
