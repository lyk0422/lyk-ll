package com.example.starter.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 重要用户负荷清单条目。
 *
 * @param userName 重要用户名称（合成数据）
 * @param loadKw   负荷，单位kW，正整数
 */
public record LoadItem(
        @NotBlank String userName,
        @NotNull @Min(1) Integer loadKw) {
}
