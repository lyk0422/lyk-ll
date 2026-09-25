package com.example.starter.marpol.domain;

/**
 * 航次状态。
 */
public enum VoyageStatus {
    /** 开放：允许新增排放记录。 */
    OPEN,
    /** 已关闭：关闭后新增记录一律拒绝。 */
    CLOSED
}
