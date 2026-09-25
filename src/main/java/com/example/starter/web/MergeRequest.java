package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 工单合并请求：多个原单合并为一张新单，原单引用新单并保留轨迹。
 */
public record MergeRequest(
        @NotBlank String requestId,
        @NotEmpty List<@NotBlank String> sourceOrderNos,
        String description) {
}
