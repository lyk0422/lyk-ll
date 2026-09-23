package com.example.starter.specimen.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 入库请求：specimenId 唯一；volumeUl 单位微升，最多三位小数；custodian 为初始保管方。
 */
public record IntakeRequest(
        @NotBlank String specimenId,
        @NotNull @Positive BigDecimal volumeUl,
        @NotBlank String custodian) {
}
