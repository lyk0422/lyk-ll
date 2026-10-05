package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 初报请求。
 *
 * @param requestId       幂等键，非空
 * @param patientId       患者证件号（合成），非空
 * @param patientName     患者姓名，非空
 * @param age             年龄（岁）
 * @param gender          性别
 * @param diseaseCode     病种编码，非空
 * @param caseType        病例类型 SUSPECTED/CONFIRMED
 * @param onsetDate       发病日期（UTC 口径）
 * @param diagnosisTime   诊断时刻（UTC），报告时限起算点
 * @param institutionCode 报告医疗机构编码
 * @param reporter        报告人
 * @param address         现住址（合成）
 * @param phone           联系电话（合成）
 * @param lateReason      迟报原因；迟报时强制非空
 * @param exposureSiteCode 暴露场所编码（合成场所库），可空；为空时按现住址关键词自动匹配，未匹配不参与聚集归并
 */
public record SubmitCardRequest(
        @NotBlank String requestId,
        @NotBlank String patientId,
        @NotBlank String patientName,
        Integer age,
        String gender,
        @NotBlank String diseaseCode,
        @NotBlank @Pattern(regexp = "SUSPECTED|CONFIRMED") String caseType,
        @NotNull LocalDate onsetDate,
        @NotNull @PastOrPresent Instant diagnosisTime,
        @NotBlank String institutionCode,
        @NotBlank String reporter,
        String address,
        String phone,
        String lateReason,
        String exposureSiteCode
) {
    /** 兼容旧签名：不含暴露场所，按地址关键词自动匹配。 */
    public SubmitCardRequest(String requestId, String patientId, String patientName, Integer age,
                             String gender, String diseaseCode, String caseType, LocalDate onsetDate,
                             Instant diagnosisTime, String institutionCode, String reporter,
                             String address, String phone, String lateReason) {
        this(requestId, patientId, patientName, age, gender, diseaseCode, caseType, onsetDate,
                diagnosisTime, institutionCode, reporter, address, phone, lateReason, null);
    }
}
