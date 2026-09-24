package com.example.starter.ili.persistence;

import com.example.starter.ili.model.Grade;
import com.example.starter.ili.model.ReviewStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * match_pair 表行记录。
 *
 * @param id            匹配对主键
 * @param batchId       所属批次
 * @param oldReadingId  旧回合读数主键
 * @param newReadingId  新回合读数主键
 * @param distanceM     里程差绝对值，单位米
 * @param growthRatePct 深度年增长率，单位 %/年
 * @param grade         系统分级
 * @param reviewStatus  复核状态
 * @param reviewGrade   复核降级后的分级，NULL 表示未降级
 * @param reviewer      复核人，NULL 表示未复核
 * @param reviewedAt    复核时间，NULL 表示未复核
 */
public record MatchRow(long id, long batchId, long oldReadingId, long newReadingId,
                       BigDecimal distanceM, BigDecimal growthRatePct, Grade grade,
                       ReviewStatus reviewStatus, Grade reviewGrade,
                       String reviewer, LocalDateTime reviewedAt) {

    /**
     * 生效分级：已降级取复核分级，否则取系统分级。
     */
    public Grade effectiveGrade() {
        return reviewGrade != null ? reviewGrade : grade;
    }
}
