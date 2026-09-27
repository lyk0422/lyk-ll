package com.example.rebate.web.dto;

/**
 * 冲回单详情视图：冲回单＋追缴明细（未批准时为 {@code null}）。
 */
public record ClawbackDetailView(
        ClawbackView clawback,
        RecoveryView recovery) {
}
