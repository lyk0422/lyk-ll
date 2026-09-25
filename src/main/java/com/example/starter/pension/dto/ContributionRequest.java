package com.example.starter.pension.dto;

import java.time.LocalDate;

import com.example.starter.pension.domain.ContributionSource;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 缴费登记请求（合成数据），金额精确到分。
 *
 * @param memberNo         成员编号
 * @param contributionDate 缴费日期（UTC）
 * @param source           缴费来源：EMPLOYER 企业 / EMPLOYEE 个人
 * @param amountCents      缴费金额，单位：分，非负
 */
public record ContributionRequest(
        @NotBlank(message = "成员编号不能为空") String memberNo,
        @NotNull(message = "缴费日期不能为空") LocalDate contributionDate,
        @NotNull(message = "缴费来源不能为空") ContributionSource source,
        @Min(value = 0, message = "缴费金额不能为负") long amountCents) {
}
