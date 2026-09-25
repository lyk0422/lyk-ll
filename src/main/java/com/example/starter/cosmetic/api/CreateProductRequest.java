package com.example.starter.cosmetic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建产品请求（合成数据）。
 */
public record CreateProductRequest(
        @NotBlank(message = "产品名称必填") String name,
        @NotBlank(message = "备案人必填") String applicant) {
}
