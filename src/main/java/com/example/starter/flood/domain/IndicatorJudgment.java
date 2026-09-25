package com.example.starter.flood.domain;

/**
 * 单项监测指标判定（干滩长度、浸润线埋深），低于安全下限判险、恰等下限判临界。
 */
public enum IndicatorJudgment {
    /** 高于安全下限。 */
    SAFE,
    /** 恰等于安全下限：临界。 */
    CRITICAL,
    /** 低于安全下限：险。 */
    DANGER
}
