package com.example.starter.forestry.domain;

/**
 * 迹地更新义务状态：
 * PENDING（待验收）-> PASSED（验收通过，终态）/ REPLANTING（验收不合格，补植中）
 * REPLANTING -> PASSED（补植后通过）/ FAILED（补植仍不合格，终态，触发限批）。
 */
public enum RenewalStatus {
    PENDING,
    REPLANTING,
    PASSED,
    FAILED
}
