package com.example.starter.pension.dto;

import java.time.LocalDate;

import com.example.starter.pension.domain.EmploymentStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

/**
 * 成员登记请求（合成数据）。
 *
 * @param memberNo 成员唯一编号
 * @param name     姓名
 * @param hireDate 入职日期（UTC 日界）
 * @param status   在职状态
 */
public record MemberRequest(
        @NotBlank(message = "成员编号不能为空") String memberNo,
        @NotBlank(message = "姓名不能为空") String name,
        @NotNull(message = "入职日期不能为空") @PastOrPresent(message = "入职日期不能晚于今天") LocalDate hireDate,
        @NotNull(message = "在职状态不能为空") EmploymentStatus status) {
}
