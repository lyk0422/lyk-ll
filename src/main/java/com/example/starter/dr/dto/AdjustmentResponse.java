package com.example.starter.dr.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冻结后更正形成的独立调整建议视图。
 *
 * @param id                           建议 ID
 * @param eventRef                     事件业务编码
 * @param meterId                      电表业务编码
 * @param intervalIdx                  区间编号
 * @param dataType                     被更正数据类型
 * @param frozenVersion                冻结快照中的版本号
 * @param frozenValue                  冻结快照中的数值（kW）
 * @param correctedVersion             更正后的新版本号
 * @param correctedValue               更正后的新数值（kW）
 * @param frozenIntervalDelivered      冻结的区间交付量
 * @param suggestedIntervalDelivered   按更正数据重算的建议区间交付量
 * @param suggestedDelta               建议调整量（正=应补，负=应退）
 * @param status                       建议状态
 * @param createdAtUtc                 建议生成时刻 UTC
 */
public record AdjustmentResponse(
        Long id,
        String eventRef,
        String meterId,
        int intervalIdx,
        String dataType,
        int frozenVersion,
        BigDecimal frozenValue,
        int correctedVersion,
        BigDecimal correctedValue,
        BigDecimal frozenIntervalDelivered,
        BigDecimal suggestedIntervalDelivered,
        BigDecimal suggestedDelta,
        String status,
        Instant createdAtUtc) {
}
