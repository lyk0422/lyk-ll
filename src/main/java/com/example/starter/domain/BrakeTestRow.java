package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 制动试验记录行(对应 brake_test 表)。
 */
public record BrakeTestRow(long id, long operationId, int consistVersion, TestPhase phase,
                           int reductionKpa, Boolean allCarsBraked, Boolean emergencyOccurred,
                           TestResult result, boolean valid, LocalDateTime createdAt) {
}
