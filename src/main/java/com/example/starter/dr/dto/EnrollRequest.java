package com.example.starter.dr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * 登记参与电表请求。
 *
 * @param meterId         电表业务编码
 * @param committedValue  承诺单区间最大削减功率（kW，非负有限小数）
 * @param baselineVersion 登记时声明的基线版本标签
 */
public record EnrollRequest(
        @NotBlank String meterId,
        @NotNull @PositiveOrZero BigDecimal committedValue,
        @NotBlank String baselineVersion) {
}
