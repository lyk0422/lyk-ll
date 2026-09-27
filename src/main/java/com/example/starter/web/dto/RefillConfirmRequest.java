package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补液交接确认请求：须两名不同交接人共同确认，浓度在许可闭区间且加入后不超容量才入账。
 *
 * @param stationCode       门站编码
 * @param requestId         幂等键
 * @param batchNo           运输批号，全局唯一
 * @param quantityKg        补液数量 kg，三位小数
 * @param concentrationMgM3 合成验收浓度 mg/m3
 * @param firstOperator     第一名交接人工号
 * @param secondOperator    第二名交接人工号，须与第一人不同
 * @param confirmedAt       确认时刻（业务时间）
 * @param expectedVersion   期望的储罐版本
 */
public record RefillConfirmRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantityKg,
        @NotNull @DecimalMin("0") BigDecimal concentrationMgM3,
        @NotBlank String firstOperator,
        @NotBlank String secondOperator,
        @NotNull LocalDateTime confirmedAt,
        @NotNull Integer expectedVersion) {
}
