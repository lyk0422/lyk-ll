package com.example.starter.tank;

import java.math.BigDecimal;

/**
 * 压力联动触发的内部预占裁决结果（不对外暴露 HTTP 结构）。
 *
 * @param issued          是否成功预占（true 加注指令可随联动下发；false 被安全储备拦截）
 * @param planId          成功预占的加注计划ID（拦截时为 null）
 * @param reservedDoseKg  成功预占量 kg（拦截时为 0）
 * @param pendingCreated  是否因此生成待补液事件
 * @param version         裁决后储罐版本（未绑定储罐时为 null）
 */
public record PressureReservationOutcome(
        boolean issued,
        Long planId,
        BigDecimal reservedDoseKg,
        boolean pendingCreated,
        Integer version) {

    public static PressureReservationOutcome notBound() {
        return new PressureReservationOutcome(true, null, null, false, null);
    }
}
