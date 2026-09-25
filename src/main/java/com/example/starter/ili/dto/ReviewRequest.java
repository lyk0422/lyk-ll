package com.example.starter.ili.dto;

import com.example.starter.ili.ReviewDecision;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 复核结论提交请求：对增长、严重缺陷逐条确认或降级。
 *
 * @param pairId   匹配对主键
 * @param decision 复核动作：CONFIRM=确认，DOWNGRADE=降级
 * @param reviewer 复核人标识
 * @param comment  复核备注，可为空
 */
public record ReviewRequest(
        @NotNull Long pairId,
        @NotNull ReviewDecision decision,
        @NotBlank @Size(max = 64) String reviewer,
        @Size(max = 512) String comment) {
}
