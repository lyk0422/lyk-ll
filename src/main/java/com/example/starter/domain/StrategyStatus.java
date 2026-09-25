package com.example.starter.domain;

/**
 * 气候策略生命周期状态：编制→校验→下发→执行→（被新策略替换）失效→评估。
 */
public enum StrategyStatus {
    /** 编制中，尚未通过窗口校验。 */
    DRAFT,
    /** 校验通过：设定点位于生育期窗口内且昼夜温差合法。 */
    VALIDATED,
    /** 已下发到分区并在生效时刻起生效。 */
    DISPATCHED,
    /** 已被同分区更新的策略替换，在新生效时刻同一事务内失效。 */
    EXPIRED,
    /** 已纳入日评估。 */
    EVALUATED
}
