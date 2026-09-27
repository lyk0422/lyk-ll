package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 列尾装置绑定请求：受控作业绑定一台空闲装置与当前编组尾车。
 *
 * @param requestId       幂等请求键
 * @param deviceNo        装置编号
 * @param expectedVersion 装置期望版本（自1起；与当前版本不一致返回409，成功后版本加1）
 */
public record EotBindRequest(
        @NotBlank String requestId,
        @NotBlank String deviceNo,
        @NotNull @Positive Integer expectedVersion) {
}
