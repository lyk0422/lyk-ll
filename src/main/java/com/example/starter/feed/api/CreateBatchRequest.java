package com.example.starter.feed.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 养殖批次建档请求（合成数据）。
 *
 * @param batchNo 批次号
 * @param species 动物种类
 */
public record CreateBatchRequest(
        @NotBlank String batchNo,
        @NotBlank String species) {
}
