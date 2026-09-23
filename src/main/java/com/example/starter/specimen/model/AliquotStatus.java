package com.example.starter.specimen.model;

/**
 * 分装状态：AVAILABLE 可用；RESERVED 已被检测申请预留；CONSUMED 已消耗；QUARANTINED 已污染隔离。
 */
public enum AliquotStatus {
    AVAILABLE,
    RESERVED,
    CONSUMED,
    QUARANTINED
}
