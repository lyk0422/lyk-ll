package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 室内检测记录（发芽率）。首检与复检各一条，复检为唯一有效结论时首检仍保留可查。
 *
 * @param id              主键
 * @param lotNo           批次编号
 * @param requestId       提交幂等键
 * @param germinationRate 发芽率（百分数，%）
 * @param verdict         结论（按申报等级门槛判定）
 * @param round           检测轮次：1=首检，2=复检
 * @param effective       是否当前有效结论
 * @param retestable      首检不合格时是否仍在可复检期限内（动态视图字段，库中不落该列）
 * @param inspector       检测员
 * @param inspectedAt     检测时间（UTC）
 * @param createdAt       记录创建时间（UTC）
 */
public record LabInspectionRecord(
        Long id,
        String lotNo,
        String requestId,
        BigDecimal germinationRate,
        InspectionVerdict verdict,
        int round,
        boolean effective,
        boolean retestable,
        String inspector,
        Instant inspectedAt,
        Instant createdAt
) {

    public LabInspectionRecord withEffective(boolean value) {
        return new LabInspectionRecord(id, lotNo, requestId, germinationRate, verdict, round, value,
                retestable, inspector, inspectedAt, createdAt);
    }
}
