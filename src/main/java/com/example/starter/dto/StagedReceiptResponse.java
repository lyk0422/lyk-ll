package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 分钟回执响应。
 * executed=false 且 paused=true 表示每分钟重查机组可用状态时发现失效机组：
 * 该分钟未执行（零更新）、计划已暂停，已执行历史不回退。
 * completed=true 表示最后一分钟确认完成，指令完成。
 */
public record StagedReceiptResponse(
        String requestId,
        String orderNo,
        Long planId,
        int planVersion,
        int minuteNo,
        Instant minuteAt,
        boolean executed,
        boolean paused,
        boolean completed,
        int currentMinute,
        List<StagedReceiptItem> applied,
        boolean replayed) {

    public StagedReceiptResponse asReplay() {
        return new StagedReceiptResponse(requestId, orderNo, planId, planVersion, minuteNo,
                minuteAt, executed, paused, completed, currentMinute, applied, true);
    }
}
