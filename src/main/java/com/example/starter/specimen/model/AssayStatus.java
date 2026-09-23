package com.example.starter.specimen.model;

/**
 * 检测申请状态：RESERVED 已预留未开始；CONSUMED 已开始并消耗占用量；CANCELLED 已取消。
 */
public enum AssayStatus {
    RESERVED,
    CONSUMED,
    CANCELLED
}
