package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 升温操作票创建请求（以 requestId 幂等）。创建即按合成曲线进入第一阶段。
 *
 * @param requestId    创建幂等键
 * @param kilnId       窑编号
 * @param curveVersion 合成升温曲线版本
 * @param stages       曲线阶段定义，至少一段，按顺序执行
 */
public record WarmupTicketCreateRequest(
        @NotBlank String requestId,
        @NotBlank String kilnId,
        @NotBlank String curveVersion,
        @NotEmpty @Valid List<WarmupStageSpec> stages) {
}
