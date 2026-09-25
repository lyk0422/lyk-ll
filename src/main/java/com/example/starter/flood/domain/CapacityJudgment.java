package com.example.starter.flood.domain;

/**
 * 库容调洪判定：以“设计洪水量”对比“当前水位可用调洪库容”。
 */
public enum CapacityJudgment {
    /** 设计洪水量小于可用库容：安全。 */
    SAFE,
    /** 设计洪水量恰等于可用库容：临界。 */
    CRITICAL,
    /** 设计洪水量大于可用库容：须泄流。 */
    RELEASE_REQUIRED
}
