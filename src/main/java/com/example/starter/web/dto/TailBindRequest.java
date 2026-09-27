package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 列尾装置绑定请求：受控作业绑定一台空闲且自检合格的装置与当前编组尾车。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 装置期望版本（乐观锁），成功绑定后版本加 1
 * @param deviceNo        装置编号
 * @param tailCarNo       当前编组尾车号（绑定时刻冻结）
 * @param boundAt         绑定时刻（UTC 精确时刻），须 now 语义下早于自检截止
 */
public record TailBindRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String deviceNo,
        @NotBlank String tailCarNo,
        @NotNull Instant boundAt) {
}
