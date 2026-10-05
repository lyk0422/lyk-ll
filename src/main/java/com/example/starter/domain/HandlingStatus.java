package com.example.starter.domain;

/**
 * 告警处置生命周期状态：发起→进行中→完结（终态）。
 */
public enum HandlingStatus {
    /** 处置进行中：参与同分区新策略下发的兼容性仲裁。 */
    IN_PROGRESS,
    /** 处置完结：终态，不再参与仲裁。 */
    COMPLETED
}
