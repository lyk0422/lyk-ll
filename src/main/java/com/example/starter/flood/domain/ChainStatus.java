package com.example.starter.flood.domain;

/**
 * 处置链与方案生命周期状态。
 */
public enum ChainStatus {
    /** 处置链生效中，可继续演算/下达指令；方案执行中。 */
    ACTIVE,
    /** 方案已回执完成（不再执行中）。 */
    COMPLETED,
    /** 终态：已复盘归档，重复归档幂等返回；迟到读数只记事件。 */
    ARCHIVED
}
