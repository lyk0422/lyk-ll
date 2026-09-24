package com.example.starter.blood.web;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 入库请求。
 *
 * @param barcode       唯一条码
 * @param componentType 成分类型（合成数据）
 * @param expireDate    效期日期（UTC 日历日，可放行截止为当日 23:59:59 UTC）
 */
public record CheckInRequest(
        @NotBlank @Size(max = 64) String barcode,
        @NotBlank @Size(max = 64) String componentType,
        @NotNull LocalDate expireDate) {
}
