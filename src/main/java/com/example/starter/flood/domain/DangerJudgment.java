package com.example.starter.flood.domain;

/**
 * 调洪演算综合判定（库容判定与双指标判定合并，优先级 险 &gt; 须泄流 &gt; 临界 &gt; 安全）。
 */
public enum DangerJudgment {
    /** 库容安全且双指标均高于下限。 */
    SAFE,
    /** 库容恰等，或任一指标恰等于安全下限。 */
    CRITICAL,
    /** 设计洪水量超出可用调洪库容，须下达泄流方案。 */
    RELEASE_REQUIRED,
    /** 干滩长度或浸润线埋深任一低于安全下限。 */
    DANGER
}
