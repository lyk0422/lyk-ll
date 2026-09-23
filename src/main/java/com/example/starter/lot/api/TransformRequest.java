package com.example.starter.lot.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 转换请求：原子消费多个父批次并产生多个新子批次。
 *
 * @param requestId    业务幂等键；同键同完整参数返回首次结果，同键异参返回 409
 * @param parents      父批次消费列表（含 expectedVersion）
 * @param children     子批次产出列表（新批次 ID）
 * @param allowedLoss  本次允许损耗上限 = 总消费 - 总产出 的最大差额，非负三位小数
 * @param unit         本次转换统一计量单位
 */
public record TransformRequest(
        @NotBlank String requestId,
        @NotEmpty @Valid List<ParentInput> parents,
        @NotEmpty @Valid List<ChildOutput> children,
        @NotNull @DecimalMin(value = "0.000", inclusive = true)
        @Digits(integer = 16, fraction = 3) BigDecimal allowedLoss,
        @NotBlank String unit
) {
}
