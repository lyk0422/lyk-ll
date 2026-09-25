package com.example.starter.dto;

import com.example.starter.domain.TestPhase;
import com.example.starter.domain.TestResult;

/**
 * 制动试验响应。
 */
public record BrakeTestResponse(long operationId, int consistVersion, TestPhase phase,
                                int reductionKpa, TestResult result) {
}
