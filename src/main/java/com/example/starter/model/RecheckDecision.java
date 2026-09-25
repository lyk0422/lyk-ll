package com.example.starter.model;

/**
 * 复测处置决策。
 */
public enum RecheckDecision {
    /** 梯度合格，待归档 */
    PASSED,
    /** 梯度仍超标且未满两轮，允许二次通风 */
    SECOND_VENTILATION,
    /** 两轮通风后梯度仍超标，转人工处置 */
    MANUAL_HANDLING
}
