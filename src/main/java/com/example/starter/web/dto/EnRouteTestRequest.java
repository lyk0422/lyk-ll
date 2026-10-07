package com.example.starter.web.dto;

import com.example.starter.domain.TestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 途中制动试验请求；绑定处置单与当前编组版本，复用始发试验数值规则。
 *
 * @param requestId          幂等请求键
 * @param expectedVersion    处置单期望版本（成功变更加1，旧版409）
 * @param testType           试验类型：SENSITIVITY 感度 / STABILITY 安定
 * @param pressureReduction  减压量，单位 kPa
 * @param pipePressureBefore 试验前列车管风压，单位 kPa；须恢复至定压 500kPa（恰达允许）
 * @param allCarsBraked      感度试验：全列是否均制动
 * @param emergencyOccurred  安定试验：是否发生紧急制动
 */
public record EnRouteTestRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotNull TestType testType,
        @NotNull @PositiveOrZero Integer pressureReduction,
        @NotNull @PositiveOrZero Integer pipePressureBefore,
        Boolean allCarsBraked,
        Boolean emergencyOccurred) {
}
