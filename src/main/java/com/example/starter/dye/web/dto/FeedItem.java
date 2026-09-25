package com.example.starter.dye.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 投料称量明细：客户端只报实际称量值，计划克重由服务端按浴比换算，不可被客户端覆盖。
 *
 * @param actualGrams 实际称量克重，单位克，必须为正
 */
public record FeedItem(
        @NotBlank String dyestuffCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal actualGrams) {
}
