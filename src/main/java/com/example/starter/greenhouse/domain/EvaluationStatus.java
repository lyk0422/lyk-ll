package com.example.starter.greenhouse.domain;

/**
 * 日评估状态：OPEN 可反复重算；SEALED 封存终态，重复封存幂等返回首次结果。
 */
public enum EvaluationStatus {
    OPEN,
    SEALED
}
