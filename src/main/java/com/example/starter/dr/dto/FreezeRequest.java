package com.example.starter.dr.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 结算冻结请求。
 *
 * @param requestId       冻结请求幂等键：同键同参重放返回首次结果，同键异参返回 409
 * @param expectedVersion 调用方期望的事件聚合版本（登记电表次数对应版本），冲突返回 409
 * @param meters          每块电表逐区间的基线/实测证据版本
 */
public record FreezeRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotEmpty @Valid List<MeterEvidence> meters) {
}
