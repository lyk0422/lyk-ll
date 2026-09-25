package com.example.starter.dye.domain;

/**
 * 幂等操作类型，对应染色流转的四类写操作。
 */
public enum OperationType {
    /** 打小样对色 */
    COLOR_MATCH,
    /** 投料执行 */
    FEED,
    /** 缸差判定 */
    JUDGE,
    /** 判定归档 */
    ARCHIVE
}
