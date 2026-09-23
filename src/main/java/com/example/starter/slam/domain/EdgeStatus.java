package com.example.starter.slam.domain;

/**
 * 边的审计状态：有效进入位姿图，或回环校验未通过被拒绝（仅审计留存）。
 */
public enum EdgeStatus {
    /**
     * 校验通过并进入有效图，参与图版本递增。
     */
    VALID,
    /**
     * 回环约束未通过版本化阈值校验，仅保存审计记录，不进入有效图。
     */
    REJECTED
}
