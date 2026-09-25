package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 订正请求：字段为 null 表示该字段沿用当前最新版本；非 null 字段构成新版本完整快照的一部分。
 *
 * @param requestId     幂等键，非空
 * @param cardNo        被订正卡片编号，非空
 * @param diseaseCode   订正后病种编码；非 null 且与现版本不同属于病种变更，须重新起算审核
 * @param caseType      订正后病例类型；由疑似转确诊属于病种变更，须重新起算审核
 * @param patientName   订正后姓名
 * @param age           订正后年龄
 * @param gender        订正后性别
 * @param onsetDate     订正后发病日期（UTC 口径）
 * @param diagnosisTime 订正后诊断时刻（UTC）
 * @param institutionCode 订正后报告机构
 * @param reporter      订正后报告人
 * @param address       订正后住址
 * @param phone         订正后电话
 * @param lateReason    订正后迟报原因；新版本迟报时强制非空
 * @param changeReason  订正原因
 */
public record CorrectCardRequest(
        @NotBlank String requestId,
        @NotBlank String cardNo,
        String diseaseCode,
        @Pattern(regexp = "SUSPECTED|CONFIRMED") String caseType,
        String patientName,
        Integer age,
        String gender,
        LocalDate onsetDate,
        @PastOrPresent Instant diagnosisTime,
        String institutionCode,
        String reporter,
        String address,
        String phone,
        String lateReason,
        String changeReason
) {
}
