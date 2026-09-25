package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 摘挂请求: 在同一事务内变更编组并作废原试验结论。
 *
 * @param requestId    幂等键
 * @param attachCars   挂车清单(可为空)
 * @param detachCarNos 摘车车辆号清单(可为空)
 */
public record ShuntRequest(
        @NotBlank String requestId,
        List<@Valid CarInput> attachCars,
        List<String> detachCarNos) {
}
