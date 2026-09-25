package com.example.starter.greenhouse.domain;

/**
 * 幂等业务类型，区分 requestId 所在的业务场景
 * （策略下发 / 告警确认 / 环控指令下发）。
 */
public enum IdempotentBizType {
    STRATEGY_ACTIVATE,
    ALERT_ACKNOWLEDGE,
    COMMAND_ISSUE
}
