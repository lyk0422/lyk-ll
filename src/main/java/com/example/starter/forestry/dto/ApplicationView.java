package com.example.starter.forestry.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采伐申请单视图。
 *
 * @param id            申请单 ID
 * @param status        申请状态
 * @param operatorId    经营主体 ID
 * @param subplotId     小班 ID
 * @param speciesCode   树种编码（限额档位）
 * @param quotaYear     限额年度
 * @param harvestType   采伐类型
 * @param treeCount     申请采伐株数，单位株
 * @param designVolume  蓄积设计值（申请人填报/补正后），立方米
 * @param checkedVolume 系统按合成蓄积表复核的蓄积，立方米
 * @param licenseId     关联采伐证 ID，未发证为空
 * @param rejectReason  拒绝原因，未拒绝为空
 * @param createdAt     创建日期（UTC）
 * @param archivedDate  归档日期（UTC），未归档为空
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
        BigDecimal designVolume,
        BigDecimal checkedVolume,
        String licenseId,
        String rejectReason,
        LocalDate createdAt,
        LocalDate archivedDate
) {
}
