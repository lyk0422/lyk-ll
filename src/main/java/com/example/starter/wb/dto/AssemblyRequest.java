package com.example.starter.wb.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 集装器装配图提交请求。
 *
 * @param requestId       幂等请求 ID
 * @param expectedVersion 客户端所见装配图版本；无图首版为 0，此后须为当前版本（乐观并发）
 * @param occurredAt      业务发生精确时刻（Asia/Shanghai），参与指纹
 * @param operator        提交人岗位账号
 * @param items           集装器装配项集合（非空）
 */
public record AssemblyRequest(
        @NotBlank String requestId,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotNull LocalDateTime occurredAt,
        @NotBlank String operator,
        @NotEmpty List<@Valid UldItem> items
) {
}
