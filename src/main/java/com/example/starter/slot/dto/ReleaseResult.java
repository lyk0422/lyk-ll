package com.example.starter.slot.dto;

/**
 * 系列退回结果。
 *
 * @param requestId 原系列申请幂等键
 * @param status    退回后系列状态，固定 RELEASED
 * @param replay    是否为同键重复退回（幂等）
 */
public record ReleaseResult(
        String requestId,
        String status,
        boolean replay) {
}
