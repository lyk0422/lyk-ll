package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 到期轧辊返修放行申请请求。
 *
 * <p>仅当旧辊号是某物理辊的当前代次、累计里程已达周期上限（禁排）且已日冻结时方可申请。</p>
 *
 * @param requestId             幂等键，指纹覆盖该键与全部业务字段
 * @param physicalNo            返修对象物理辊编号；旧辊号须属于该物理辊，跨物理辊引用拒绝
 * @param oldRollNo             旧代次辊号（当前代次）
 * @param newRollNo             拟用新辊号；不得占用任何已有辊号
 * @param diameterAfterGrindMm  磨后直径（毫米，保留两位小数，不少于 500 且不得比上一代大）
 * @param cycleLimitKm          新代周期公里上限（正数且不超过上一代上限，单位：公里）
 * @param inspectionCertNo      检验凭据编号
 * @param mountedAt             新辊拟上机时刻（业务时间，UTC），其后前 5 卷为首用烫辊位
 */
public record ReworkApplicationRequest(
        @NotBlank String requestId,
        @NotBlank String physicalNo,
        @NotBlank String oldRollNo,
        @NotBlank String newRollNo,
        @NotNull BigDecimal diameterAfterGrindMm,
        @NotNull @Positive BigDecimal cycleLimitKm,
        @NotBlank String inspectionCertNo,
        @NotNull LocalDateTime mountedAt) {
}
