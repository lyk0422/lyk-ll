package com.example.starter.flood.domain;

/**
 * 滚动演算泄流能力判定：当前方案泄流能力对比入库洪水过程线剩余段峰值需求。
 */
public enum ReleaseJudgment {
    /** 泄流能力大于需求：充足。 */
    SUFFICIENT,
    /** 泄流能力恰等于需求：临界（不判不足，不触发提级）。 */
    CRITICAL,
    /** 泄流能力小于需求：不足，须提升预警级别并追加泄流方案版本。 */
    INSUFFICIENT
}
