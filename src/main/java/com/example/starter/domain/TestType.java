package com.example.starter.domain;

/**
 * 制动试验类型：SENSITIVITY 感度试验（减压 50kPa 全列须制动）；STABILITY 安定试验（减压 140kPa 不得紧急制动）。
 */
public enum TestType {
    SENSITIVITY,
    STABILITY
}
