package com.example.starter.epidemic.api;

import com.example.starter.epidemic.domain.CardStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 卡片详情（默认返回最新版本）。
 *
 * @param duplicate       是否为重复报告命中（true 时未新建卡片）
 * @param cardNo          卡片编号
 * @param versionNo       当前版本号
 * @param status          卡片状态
 * @param diseaseCode     病种编码
 * @param diseaseName     病种名称
 * @param category        病种分类 A/B
 * @param caseType        病例类型 SUSPECTED/CONFIRMED
 * @param patientId       患者证件号
 * @param patientName     患者姓名
 * @param age             年龄（岁）
 * @param gender          性别
 * @param onsetDate       发病日期（UTC 口径）
 * @param diagnosisTime   诊断时刻（UTC）
 * @param institutionCode 医疗机构编码
 * @param reporter        报告人
 * @param address         现住址
 * @param phone           联系电话
 * @param late            是否迟报
 * @param lateReason      迟报原因（及时为 null）
 * @param deadlineAt      报告时限终点（UTC，含端点：恰在此时刻提交为及时）
 * @param submittedAt     该版本提交时刻（UTC）
 * @param updatedAt       卡片最近更新时刻（UTC）
 */
public record CardResponse(
        boolean duplicate,
        String cardNo,
        int versionNo,
        CardStatus status,
        String diseaseCode,
        String diseaseName,
        String category,
        String caseType,
        String patientId,
        String patientName,
        Integer age,
        String gender,
        LocalDate onsetDate,
        Instant diagnosisTime,
        String institutionCode,
        String reporter,
        String address,
        String phone,
        boolean late,
        String lateReason,
        Instant deadlineAt,
        Instant submittedAt,
        Instant updatedAt
) {
}
