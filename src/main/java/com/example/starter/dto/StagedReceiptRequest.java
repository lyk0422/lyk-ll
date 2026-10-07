package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 受控分段计划分钟回执请求。
 * 整分钟一起确认：items 须恰好覆盖全部参与机组且功率等于冻结阶梯；
 * 缺一机组零更新；分钟序号不可提前、不可跳号。
 *
 * @param requestId 幂等键，覆盖全部业务字段
 * @param orderNo   指令单号（其分段计划为回执目标）
 * @param minuteNo  分钟序号（从 1 起，须等于当前进度+1）
 * @param items     全部参与机组该分钟功率
 */
public record StagedReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String orderNo,
        @Positive int minuteNo,
        @NotEmpty List<StagedReceiptItem> items) {
}
