package com.example.starter.runway;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 巡检评估记录行：对应 assessment_record 表，系数轨迹按 assessed_at 排序可复算。
 *
 * @param id                 评估记录主键
 * @param eventId            所属事件ID
 * @param frictionCoefficient 摩擦系数（低于0.35持续关闭，恰0.35合格）
 * @param assessmentType     评估类型
 * @param assessedAt         评估时间（UTC）
 * @param passed             是否合格
 * @param retest             是否修复后复测
 * @param requestId          评估幂等请求标识
 * @param createdAt          记录创建时间（UTC）
 */
public record AssessmentRecord(
        long id,
        long eventId,
        BigDecimal frictionCoefficient,
        AssessmentType assessmentType,
        Instant assessedAt,
        boolean passed,
        boolean retest,
        String requestId,
        Instant createdAt) {
}
