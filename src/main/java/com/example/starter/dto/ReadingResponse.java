package com.example.starter.dto;

/**
 * 振动回传响应。
 * deduped 为 true 表示命中去重键（爆区+测点+采样时刻）被忽略；
 * late 为 true 表示封档后到达，仅记录迟到事件；
 * freezeTriggered 为 true 表示本次读数触发矿区冻结。
 */
public record ReadingResponse(
        long permitId,
        String pointCode,
        boolean accepted,
        boolean deduped,
        boolean late,
        boolean freezeTriggered) {
}
