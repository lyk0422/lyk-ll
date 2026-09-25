package com.example.starter.dye.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 排缸请求：指定染缸、已发布配方版本与织物重量，服务端按配方浴比放大。
 *
 * @param recipeVersion null 表示使用该配方最新发布版本
 * @param mergeWithRunNo 并缸伙伴缸次编号；非空时须同配方同浴比，否则拒绝
 */
public record ScheduleRunRequest(
        @NotBlank String runNo,
        @NotBlank String vatCode,
        @NotBlank String recipeCode,
        Integer recipeVersion,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal fabricKg,
        String mergeWithRunNo) {
}
