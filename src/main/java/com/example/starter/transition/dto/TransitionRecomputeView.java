package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 过渡料复算结果：过渡料长度、降级卷明细与改判链均可复算。
 */
public record TransitionRecomputeView(
        String orderNo,
        Integer switchTimeSec,
        BigDecimal machineSpeedMMin,
        BigDecimal recordedTransitionLengthM,
        BigDecimal recomputedTransitionLengthM,
        BigDecimal sumRollLengthM,
        List<RollView> rolls) {
}
