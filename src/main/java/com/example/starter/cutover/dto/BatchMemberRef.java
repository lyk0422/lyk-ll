package com.example.starter.cutover.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批次成员引用：申请 ID 及其期望版本号。
 *
 * @param expectedVersion 发布时申请必须处于的版本号（从 1 开始），不符则整批拒绝
 */
public record BatchMemberRef(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion
) {
}
