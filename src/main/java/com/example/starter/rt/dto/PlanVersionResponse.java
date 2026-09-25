package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

import com.example.starter.rt.domain.PlanStatus;

/**
 * 计划版本视图。
 *
 * @param id                      版本ID
 * @param patientId               患者ID
 * @param planCode                计划业务编码
 * @param versionNo               版本号
 * @param templateCode            约束模板编码
 * @param prescribedTotalDoseCgy  处方总剂量(cGy)
 * @param fractionDoseCgy         单次分次剂量(cGy)
 * @param fractionCount           处方分次次数
 * @param status                  当前状态
 * @param createdAt               创建时间
 * @param reviewedAt              物理审核时间（未审核为空）
 * @param approvedAt              医师批准时间（未批准为空）
 * @param verifiedAt              模体验证时间（未通过为空）
 * @param firstFractionAt         首次分次执行时间（未执行为空）
 * @param closedAt                结案时间（未结案为空）
 * @param invalidatedAt           失效时间（未失效为空）
 */
public record PlanVersionResponse(
        Long id,
        String patientId,
        String planCode,
        Integer versionNo,
        String templateCode,
        Long prescribedTotalDoseCgy,
        Long fractionDoseCgy,
        Integer fractionCount,
        PlanStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime reviewedAt,
        OffsetDateTime approvedAt,
        OffsetDateTime verifiedAt,
        OffsetDateTime firstFractionAt,
        OffsetDateTime closedAt,
        OffsetDateTime invalidatedAt) {
}
