package com.example.starter.hazmat.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 紧急改线申请请求；改线申请通过前，原路线打卡继续有效。
 *
 * @param requestId  幂等请求标识
 * @param waybillId  在途运单主键
 * @param newRouteId 新路线主键
 */
public record RerouteRequest(
        @NotBlank String requestId,
        @NotNull Long waybillId,
        @NotNull Long newRouteId) {
}
