package com.example.starter.transition.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * 改品种申请请求。
 *
 * @param requestId          幂等键
 * @param targetBlendRatioPct 目标回抄浆掺配比例，单位 %，可空；携带时须在合成允许区间内（恰达边界合法），
 *                            且申请须重新走方式判定，判定结论与比例版本在同一事务内落库
 */
public record ApplyOrderRequest(
        @NotBlank String requestId,
        @NotBlank String paperMachine,
        @NotBlank String fromGrade,
        @NotBlank String toGrade,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal targetBlendRatioPct) {

    /** 兼容无比例维度的申请。 */
    public ApplyOrderRequest(String requestId, String paperMachine, String fromGrade, String toGrade) {
        this(requestId, paperMachine, fromGrade, toGrade, null);
    }
}
