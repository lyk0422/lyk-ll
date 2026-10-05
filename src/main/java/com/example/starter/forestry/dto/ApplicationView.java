package com.example.starter.forestry.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采伐申请单视图。
 *
 * @param id             申请单 ID
 * @param status         申请状态
 * @param operatorId     经营主体 ID
 * @param subplotId      小班 ID
 * @param speciesCode    树种编码（限额档位）
 * @param quotaYear      限额年度
 * @param harvestType    采伐类型
 * @param treeCount      申请采伐株数，单位株
 * @param volume         复核口径采伐蓄积（株数 × 合成蓄积表单株蓄积），单位立方米
 * @param designedVolume 申请所附小班蓄积设计值，单位立方米
 * @param licenseId      已发采伐证 ID，未发证为空
 * @param rejectReason   拒绝原因，未拒绝为空
 * @param createdAt      申请日期（UTC）
 * @param archivedDate   归档日期（UTC），未归档为空
 */
public record ApplicationView(
        String id,
        String status,
        String operatorId,
        String subplotId,
        String speciesCode,
        Integer quotaYear,
        String harvestType,
        Integer treeCount,
        BigDecimal volume,
        BigDecimal designedVolume,
        String licenseId,
        String rejectReason,
        LocalDate createdAt,
        LocalDate archivedDate
) {
}
