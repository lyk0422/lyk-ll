package com.example.starter.transition.dto;

import java.util.List;

/**
 * 掺配轨迹复算结果：比例版本链与按版本复算的实际掺配轨迹（含复算偏离），
 * 历史订单（含已归档）均可复算。
 */
public record BlendRecomputeView(
        String orderNo,
        String status,
        Integer currentBlendVersion,
        int consecutiveOutOfTolerance,
        List<BlendVersionView> versions,
        List<BlendReadingView> readings) {
}
