package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 调节任务：初调节/再调节指令的受理与流转记录。
 *
 * @param id             主键
 * @param stationId      目标换热站 ID
 * @param unitId         目标单元 ID
 * @param requestId      客户端幂等键，全局唯一
 * @param fingerprint    幂等指纹：requestId 与全部业务字段的 SHA-256
 * @param classification 失衡分类；未执行时为 null
 * @param stepPercent    本次步进幅度，单位 %，单次不超过 10；未执行时为 null
 * @param oldOpening     调节前阀门开度，单位 %；未执行时为 null
 * @param newOpening     调节后阀门开度，单位 %；未执行时为 null
 * @param status         状态：EXECUTING / ASSESSED / ARCHIVED / MANUAL_REVIEW
 * @param executedAt     调节执行时间（UTC），稳定期起点；未执行时为 null
 * @param assessedAt     效果评估时间（UTC）；未评估时为 null
 * @param archivedAt     归档时间（UTC），归档为终态；未归档时为 null
 * @param createdAt      任务受理时间（UTC）
 */
public record AdjustmentTask(long id, long stationId, long unitId, String requestId, String fingerprint,
                             ImbalanceType classification, BigDecimal stepPercent,
                             BigDecimal oldOpening, BigDecimal newOpening,
                             TaskStatus status, Instant executedAt, Instant assessedAt,
                             Instant archivedAt, Instant createdAt) {

    /**
     * 失衡分类：欠流 / 过流。
     */
    public enum ImbalanceType {
        /** 欠流：回水温度低于站内均值，需开大阀门。 */
        UNDERFLOW,
        /** 过流：回水温度高于站内均值，需关小阀门。 */
        OVERFLOW
    }

    /**
     * 任务状态机：EXECUTING → ASSESSED → ARCHIVED（终态）；超限任务进入 MANUAL_REVIEW。
     */
    public enum TaskStatus {
        /** 执行中：阀门已调节，处于稳定期或待评估。 */
        EXECUTING,
        /** 已评估：效果评估完成，待归档。 */
        ASSESSED,
        /** 已归档：终态，重复归档幂等成功。 */
        ARCHIVED,
        /** 转人工复核：24 小时内调节次数超限，未改动阀门。 */
        MANUAL_REVIEW
    }
}
