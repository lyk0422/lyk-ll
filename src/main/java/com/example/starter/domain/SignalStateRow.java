package com.example.starter.domain;

/**
 * 信号最新状态行。
 */
public record SignalStateRow(
        String signalName,
        long rawValue,
        double physicalValue,
        String enumText,
        long frameSeq,
        long timestampNs) {
}
