package com.example.starter.waste;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 进场称重登记请求。皮重超过 7 日未更新时必须携带 tareKg 复称，复称与联单登记同事务。
 *
 * @param requestId 幂等键
 * @param plateNo 车牌号
 * @param community 收运小区
 * @param category 垃圾品类
 * @param facilityCode 去向处理设施编码，须在设施目录内
 * @param grossKg 进场毛重，单位千克
 * @param tareKg 复称皮重，单位千克；皮重有效时可省略
 */
public record EntryWeighingRequest(
        @NotBlank String requestId,
        @NotBlank String plateNo,
        @NotBlank String community,
        @NotBlank String category,
        @NotBlank String facilityCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal grossKg,
        @DecimalMin(value = "0", inclusive = false) BigDecimal tareKg) {
}
