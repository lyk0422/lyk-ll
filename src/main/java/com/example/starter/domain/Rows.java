package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据库行记录载体，仅供服务层内部使用。
 */
public final class Rows {

    private Rows() {
    }

    /** 缺陷行。registeredAt 为首次登记时刻，永不变更。 */
    public record DefectRow(
            long id,
            Long inspectionId,
            long bladeId,
            String position,
            String defectType,
            BigDecimal lengthMm,
            BigDecimal depthRatio,
            DefectLevel level,
            DefectStatus status,
            LocalDateTime registeredAt,
            LocalDateTime closedAt) {
    }

    /** 维修单行。deadlineAt = 缺陷首次登记时刻 + 7 日。 */
    public record RepairOrderRow(
            long id,
            long defectId,
            long bladeId,
            RepairOrderStatus status,
            LocalDateTime deadlineAt,
            LocalDateTime createdAt,
            LocalDateTime closedAt) {
    }

    /** 限功率行。liftedAt 为 NULL 表示仍生效。 */
    public record CurtailmentRow(
            long id,
            long bladeId,
            CurtailmentReason reason,
            Long sourceDefectId,
            Long sourceEvaluationId,
            LocalDateTime startedAt,
            LocalDateTime liftedAt) {
    }

    /** 根因评估行。 */
    public record EvaluationRow(
            long id,
            long bladeId,
            String defectType,
            long triggerDefectId,
            EvaluationStatus status,
            LocalDateTime triggeredAt,
            LocalDateTime completedAt,
            String conclusion) {
    }

    /** 逾期升级告警行。 */
    public record EscalationRow(
            long id,
            long defectId,
            LocalDateTime firstRegisteredAt,
            LocalDateTime escalatedAt,
            String message) {
    }
}
