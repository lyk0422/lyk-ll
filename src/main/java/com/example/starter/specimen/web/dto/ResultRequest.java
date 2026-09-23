package com.example.starter.specimen.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 结果回传请求：仅允许已开始的申请写入结果；取消后到达的结果返回 409。
 */
public record ResultRequest(
        @PositiveOrZero long expectedVersion,
        @NotBlank String result) {
}
