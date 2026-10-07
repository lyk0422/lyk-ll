package com.example.starter.domain;

/**
 * 途中异常处置单状态：ACTIVE 阻断中（续行须许可）；RESUMED 已续行结束（证据保留，可另报新异常）。
 */
public enum DispositionStatus {
    ACTIVE,
    RESUMED
}
