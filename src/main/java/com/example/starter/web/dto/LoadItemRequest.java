package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 重要用户保安负荷清单项。
 *
 * @param userName 重要用户名称（须为同馈线已登记重要用户，合成数据）
 * @param loadKw   保安负荷，正整数，单位kW
 */
public record LoadItemRequest(
        @NotBlank String userName,
        @NotNull @Positive Integer loadKw) {
}
