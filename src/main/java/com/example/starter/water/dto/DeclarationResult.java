package com.example.starter.water.dto;

import java.math.BigDecimal;

/**
 * 月度申报结果。
 *
 * @param status          CONFIRMED（已受理，产生新确认版本）或 REJECTED（额满等拒绝，不产生版本）
 * @param declaredVolume  原始申报水量（m³）
 * @param acceptedVolume  实际受理水量（m³）；限取减半时为申报量的 1/2
 * @param version         该自然月计划的确认版本号；拒绝时为 null
 * @param restricted      受理判定时是否处于限取状态
 * @param restrictionEffectiveAt 触发减半的限取生效时刻（UTC），未限取为 null
 * @param reason          拒绝原因；受理成功为 null
 */
public record DeclarationResult(
        String requestId,
        Long userId,
        String month,
        String status,
        BigDecimal declaredVolume,
        BigDecimal acceptedVolume,
        Integer version,
        boolean restricted,
        String restrictionEffectiveAt,
        String reason) {
}
