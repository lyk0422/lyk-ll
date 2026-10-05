package com.example.starter.forestry.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采伐许可证视图。
 *
 * @param id             许可证 ID
 * @param applicationId  关联申请单 ID
 * @param operatorId     经营主体 ID
 * @param subplotId      小班 ID
 * @param speciesCode    树种编码
 * @param quotaYear      限额年度
 * @param treeCount      证载采伐株数，单位株
 * @param volume         证载采伐蓄积量（复核口径），单位立方米
 * @param status         证件状态：ISSUED / HARVESTED / VOID
 * @param issueDate      发证日期（UTC）
 * @param expireDate     采伐期限截止日 = 发证日 + 90 天（UTC 日界）
 * @param harvestDate    实际采伐确认日期（UTC），未确认为空
 */
public record LicenseView(
        String id,
        String applicationId,
        String operatorId,
        String subplotId,
        String speciesCode,
        Integer quotaYear,
        Integer treeCount,
        BigDecimal volume,
        String status,
        LocalDate issueDate,
        LocalDate expireDate,
        LocalDate harvestDate
) {
}
