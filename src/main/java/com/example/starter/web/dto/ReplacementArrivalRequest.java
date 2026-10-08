package com.example.starter.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 热备新车到场登记请求。新车逐辆登记到场，逐车唯一；未切换前撤回则到场证据随释放失效。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的接替单当前版本号（不符409）
 * @param truckId         到场新车ID
 * @param arrivedAt       UTC精确到场时刻
 */
public record ReplacementArrivalRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull Long truckId,
        @NotNull Instant arrivedAt) {
}
