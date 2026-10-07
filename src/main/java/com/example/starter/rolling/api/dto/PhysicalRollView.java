package com.example.starter.rolling.api.dto;

/**
 * 合成物理辊登记/查询视图。
 *
 * @param physicalNo       物理辊唯一编号
 * @param headRollNo       当前代次辊号
 * @param headGenerationNo 当前代次号（从 1 起）
 * @param reworkCount      累计成功返修次数（最多 2）
 * @param version          返修聚合乐观版本（从 1 起，每次成功放行 +1）
 */
public record PhysicalRollView(
        String physicalNo,
        String headRollNo,
        Integer headGenerationNo,
        Integer reworkCount,
        Integer version) {
}
