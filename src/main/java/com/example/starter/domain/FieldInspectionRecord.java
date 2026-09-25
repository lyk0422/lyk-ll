package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 田间检验记录（品种纯度）。首检与复检各一条，复检为唯一有效结论时首检仍保留可查。
 *
 * @param id           主键
 * @param lotNo        批次编号
 * @param requestId    提交幂等键
 * @param purity       品种纯度（百分数，%）
 * @param verdict      结论（按申报等级门槛判定）
 * @param round        检验轮次：1=首检，2=复检
 * @param effective    是否当前有效结论
 * @param retestable   首检不合格时是否仍在可复检期限内（动态视图字段，库中不落该列）
 * @param inspector    检验员
 * @param inspectedAt  检验时间（UTC）
 * @param createdAt    记录创建时间（UTC）
 */
public record FieldInspectionRecord(
        Long id,
        String lotNo,
        String requestId,
        BigDecimal purity,
        InspectionVerdict verdict,
        int round,
        boolean effective,
        boolean retestable,
        String inspector,
        Instant inspectedAt,
        Instant createdAt
) {

    public FieldInspectionRecord withEffective(boolean value) {
        return new FieldInspectionRecord(id, lotNo, requestId, purity, verdict, round, value,
                retestable, inspector, inspectedAt, createdAt);
    }
}
