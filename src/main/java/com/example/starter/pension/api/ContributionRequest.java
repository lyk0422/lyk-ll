package com.example.starter.pension.api;

import com.example.starter.pension.domain.ContributionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 缴费录入请求（合成数据维护入口），金额精确到分。
 *
 * @param memberNo         成员业务编号
 * @param contributionType 缴费类型 PERSONAL/ENTERPRISE
 * @param amountCents      缴费金额，单位：分，必须为正
 * @param contributionDate 缴费归属日期（UTC 日历日）
 * @param period           缴费所属期，如 2026-01
 */
public record ContributionRequest(
        @NotBlank String memberNo,
        @NotNull ContributionType contributionType,
        @Min(1) long amountCents,
        @NotNull LocalDate contributionDate,
        @NotBlank String period) {
}
