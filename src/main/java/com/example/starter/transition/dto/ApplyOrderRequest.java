package com.example.starter.transition.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * 改品种申请请求。
 *
 * @param requestId          幂等键
 * @param targetBlendRatioPct 目标回抄浆掺配比例，单位 %，可空；携带时须在合成允许区间内（恰在边界合法），
 *                            并作为初始比例版本与申请在同一事务落库
 */
public record ApplyOrderRequest(
        @NotBlank String requestId,
        @NotBlank String paperMachine,
        @NotBlank String fromGrade,
        @NotBlank String toGrade,
        BigDecimal targetBlendRatioPct) {

    /** 不携带掺配比例的申请（保持既有调用方式）。 */
    public ApplyOrderRequest(String requestId, String paperMachine, String fromGrade, String toGrade) {
        this(requestId, paperMachine, fromGrade, toGrade, null);
    }
}
