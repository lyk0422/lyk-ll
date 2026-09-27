package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 发电车调度整体改派请求。旧单中已到场车由服务端自动保留，
 * 请求中的 vehicleIds 为“其余释放后换入的新车”集合；全方案原子切换。
 *
 * @param requestId       幂等请求号
 * @param orderId         旧调度单ID（到达超时单）
 * @param expectedVersion 旧调度单期望版本
 * @param vehicleIds      换入的新发电车ID集合（非空、不重复，不含旧单已到场车）
 * @param reassignedAt    精确 UTC 改派时刻（新单下发时刻）
 */
public record GeneratorReassignRequest(
        @NotBlank String requestId,
        @NotNull Long orderId,
        @NotNull @Positive Integer expectedVersion,
        @NotEmpty List<@NotNull Long> vehicleIds,
        @NotNull LocalDateTime reassignedAt) {
}
