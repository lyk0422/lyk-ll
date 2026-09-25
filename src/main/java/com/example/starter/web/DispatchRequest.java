package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 派单请求：将工单派给指定班组；班组已有执行中工单时低优先级工单挂单等待。
 */
public record DispatchRequest(
        @NotBlank String requestId,
        @NotBlank String orderNo,
        @NotBlank String crewCode) {
}
