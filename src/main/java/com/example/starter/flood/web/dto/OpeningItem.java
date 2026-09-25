package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 孔口组合项：方案与回执共用。
 *
 * @param name 孔口名称（如 1# 排洪隧洞检修闸门）
 * @param opening 开度，米
 * @param release 该孔口泄量，立方米每秒
 */
public record OpeningItem(
        @NotBlank String name,
        @NotNull @DecimalMin("0.0") BigDecimal opening,
        @NotNull @DecimalMin("0.0") BigDecimal release) {
}
