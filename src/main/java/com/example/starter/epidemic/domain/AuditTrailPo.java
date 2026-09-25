package com.example.starter.epidemic.domain;

import java.time.Instant;

/**
 * 审核轨迹行（不可变）：提交、审核、作废、撤回、归档、特殊订正确认均留痕，支持复算。
 *
 * @param id        自增主键
 * @param cardNo    卡片编号
 * @param versionNo 轨迹发生时的版本号
 * @param action    动作类型
 * @param operator  操作人（审核员/报告人/系统）
 * @param detail    动作说明（如作废的原审核结论）
 * @param createdAt 发生时刻（UTC）
 */
public record AuditTrailPo(
        Long id,
        String cardNo,
        int versionNo,
        AuditAction action,
        String operator,
        String detail,
        Instant createdAt
) {
}
