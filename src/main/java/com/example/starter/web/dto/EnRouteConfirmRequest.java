package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 途中新试验与列尾一致性双人共同确认请求；须两名不同人员共同确认最新尾车及编组版本。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 处置单期望版本（成功变更加1，旧版409）
 * @param tailCarNo       确认的尾车车辆号（须等于当前编组最后一辆在列车辆）
 * @param consistVersion  确认的编组版本（须等于作业当前编组版本）
 * @param confirmer1      确认人1（不得与确认人2相同）
 * @param confirmer2      确认人2（不得与确认人1相同）
 */
public record EnRouteConfirmRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String tailCarNo,
        @NotNull @Positive Integer consistVersion,
        @NotBlank String confirmer1,
        @NotBlank String confirmer2) {
}
