package com.example.starter.orchestrator.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 计划提交请求。同一 requestId 同参提交幂等，异参提交返回 409。
 *
 * @param planId 计划标识
 * @param version 计划版本
 * @param requestId 幂等请求标识
 * @param nodes 节点列表
 */
public record PlanSubmitRequest(
        @NotBlank @Size(max = 64) String planId,
        int version,
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty List<@Valid NodeSpec> nodes) {
}
