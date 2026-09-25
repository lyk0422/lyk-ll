package com.example.starter.dto;

import com.example.starter.domain.TestPhase;
import com.example.starter.domain.TestResult;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 制动试验曲线响应: 由风压读数与试验记录复算。
 */
public record BrakeCurveResponse(long operationId, List<PressurePoint> pressures, List<TestPoint> tests) {

    /**
     * 风压曲线点。
     */
    public record PressurePoint(LocalDateTime occurredAt, int pressureKpa, boolean late) {
    }

    /**
     * 试验事件点。
     */
    public record TestPoint(LocalDateTime occurredAt, TestPhase phase, int reductionKpa,
                            TestResult result, boolean valid, int consistVersion) {
    }
}
