package com.example.starter.legality.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 整组排班校验请求：一次提交机组成员名单及各自的值勤安排。
 */
public record CheckRequest(
        @NotEmpty(message = "duties 不能为空")
        @Valid
        List<MemberDutyRequest> duties
) {
}
