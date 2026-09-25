package com.example.starter.epidemic.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 报告卡版本行（不可变快照）：初报为版本 1，每次订正/特殊订正生效追加新版本，历史版本完整保留。
 *
 * @param versionNo     版本号，同一卡片内从 1 递增
 * @param changeType    版本变更类型：INITIAL 初报 / CORRECTION 订正 / SPECIAL 特殊订正
 * @param diseaseCode   病种编码
 * @param diseaseName   病种名称快照
 * @param category      病种分类快照（决定 2h/24h 时限）
 * @param caseType      病例类型：SUSPECTED 疑似 / CONFIRMED 确诊
 * @param patientId     患者证件号
 * @param patientName   患者姓名
 * @param age           患者年龄（岁）
 * @param gender        患者性别
 * @param onsetDate     发病日期（UTC 口径）
 * @param diagnosisTime 诊断时刻（UTC），报告时限起算点
 * @param institutionCode 报告医疗机构编码
 * @param reporter      报告人
 * @param address       患者现住址（合成）
 * @param phone         联系电话（合成）
 * @param late          是否迟报：提交时刻晚于“诊断时刻＋报告时限”为 true；恰在终点为 false
 * @param lateReason    迟报原因；迟报时强制非空，及时为 null
 * @param requestId     产生该版本的幂等键
 * @param submittedAt   提交时刻（UTC）
 */
public record CardVersionPo(
        Long id,
        String cardNo,
        int versionNo,
        String changeType,
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
        String requestId,
        Instant submittedAt
) {
}
