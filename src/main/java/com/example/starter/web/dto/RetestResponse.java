package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 复测结果。
 *
 * @param nextAction PASS 复测达标 / SECOND_VENTILATION 仍超标但可二次通风 / MANUAL 两轮仍超标转人工处置
 */
public record RetestResponse(
        String requestId,
        String siloCode,
        int roundNo,
        double maxTemperature,
        double minTemperature,
        double gradient,
        boolean passed,
        String nextAction,
        String state,
        LocalDateTime measuredAt
) {
}
