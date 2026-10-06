package com.example.starter.transition.dto;

/**
 * 掺配执行控制状态视图。
 */
public record BlendControlStateView(
        String orderNo,
        String controlStatus,
        Integer currentVersionNo,
        Integer outStreak) {
}
