package com.example.starter.pension.dto;

import java.time.LocalDate;

import com.example.starter.pension.domain.EmploymentStatus;

/**
 * 成员信息响应。
 */
public record MemberResponse(
        String memberNo,
        String name,
        LocalDate hireDate,
        EmploymentStatus status) {
}
