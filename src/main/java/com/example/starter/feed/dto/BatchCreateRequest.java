package com.example.starter.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 养殖批次登记请求（合成数据）。
 *
 * @param batchNo 批次号
 * @param species 批次动物种类
 */
public record BatchCreateRequest(
        @NotBlank(message = "批次号不能为空")
        @Size(max = 64)
        String batchNo,

        @NotBlank(message = "动物种类不能为空")
        @Size(max = 64)
        String species
) {
}
