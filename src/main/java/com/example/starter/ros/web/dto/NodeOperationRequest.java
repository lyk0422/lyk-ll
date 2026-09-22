package com.example.starter.ros.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 节点级操作请求,用于主动注销与模拟异常退出;requestId 保证重复请求不产生重复事件。
 */
public record NodeOperationRequest(
        @NotBlank String nodeId,
        @NotBlank String requestId
) {
}
