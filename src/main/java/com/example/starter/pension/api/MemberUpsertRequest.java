package com.example.starter.pension.api;

import com.example.starter.pension.domain.MemberStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 成员登记请求（合成数据维护入口）。
 *
 * @param memberNo 成员业务编号
 * @param name     成员姓名
 * @param hireDate 入职日期（UTC 日历日，ISO yyyy-MM-dd）
 * @param status   成员状态
 */
public record MemberUpsertRequest(
        @NotBlank String memberNo,
        @NotBlank String name,
        @NotNull LocalDate hireDate,
        @NotNull MemberStatus status) {
}
