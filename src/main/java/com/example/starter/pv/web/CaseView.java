package com.example.starter.pv.web;

import java.time.Instant;
import java.time.LocalDate;

import com.example.starter.pv.domain.CaseStatus;
import com.example.starter.pv.domain.Seriousness;

/**
 * 个例查询视图，字段均为确定值，可随时复算、结果稳定。
 *
 * @param caseNo 个例编号
 * @param patientCode 合成患者编码
 * @param patientName 合成患者姓名
 * @param drugCode 合成药品编码
 * @param drugName 合成药品名称
 * @param reactionEvent 不良反应事件
 * @param seriousness 严重程度
 * @param status 生命周期状态
 * @param awarenessDate 获知日 T0（UTC）
 * @param initialSubmittedAt 初报提交时刻（UTC）
 * @param initialDueDate 初报期限日（UTC）
 * @param onTime 初报是否及时
 * @param lateReason 迟报原因（及时为 null）
 * @param latestFollowUpDate 最近随访/并入信息日（UTC），无则 null
 * @param followUpCount 并入信息条数
 * @param relatedCaseNo 关联原个例编号，无则 null
 * @param version 当前版本号（初报为 1，每并入一条信息加 1）
 * @param createdAt 创建时刻（UTC）
 * @param updatedAt 最近更新时刻（UTC）
 */
public record CaseView(
        String caseNo,
        String patientCode,
        String patientName,
        String drugCode,
        String drugName,
        String reactionEvent,
        Seriousness seriousness,
        CaseStatus status,
        LocalDate awarenessDate,
        Instant initialSubmittedAt,
        LocalDate initialDueDate,
        boolean onTime,
        String lateReason,
        LocalDate latestFollowUpDate,
        int followUpCount,
        String relatedCaseNo,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
