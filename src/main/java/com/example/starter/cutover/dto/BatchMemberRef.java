package com.example.starter.cutover.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批次成员引用：申请编号 + 期望版本（乐观锁，版本从 1 开始）。
 */
public record BatchMemberRef(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion
) {
}
