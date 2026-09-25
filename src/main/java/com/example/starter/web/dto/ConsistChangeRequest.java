package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 摘挂变更请求；变更与试验结论作废在同一事务内完成。
 *
 * @param requestId    幂等请求键
 * @param attachCars   挂车清单（可为空列表）
 * @param detachCarNos 摘车车辆号清单（可为空列表）
 */
public record ConsistChangeRequest(
        @NotBlank String requestId,
        @NotNull List<@Valid CarDto> attachCars,
        @NotNull List<String> detachCarNos) {
}
