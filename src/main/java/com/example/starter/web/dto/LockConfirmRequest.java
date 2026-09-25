package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 锁阀／复开双人确认请求。LOCK 与 REOPEN 均须两名不同操作人。
 *
 * @param stationCode    门站编码
 * @param requestId      幂等键
 * @param action         LOCK 锁阀 / REOPEN 复开
 * @param firstOperator  第一名确认人工号
 * @param secondOperator 第二名确认人工号，须与第一人不同
 */
public record LockConfirmRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotBlank @Pattern(regexp = "LOCK|REOPEN") String action,
        @NotBlank String firstOperator,
        @NotBlank String secondOperator) {
}
