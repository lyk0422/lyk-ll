package com.example.starter.museum.api;

/**
 * 读数上报结果。
 * duplicate=true 表示同展柜同采样时刻读数已存在，按去重忽略；
 * late=true 表示合同结案后到达，仅记迟到事件；
 * alertTriggered=true 表示本次使连续 3 个采样点越限、触发环境异常。
 */
public record ReadingResponse(
        String showcaseNo,
        java.time.Instant sampleTime,
        boolean duplicate,
        boolean late,
        boolean outOfRange,
        boolean alertTriggered,
        String alertNo,
        String workOrderNo
) {
}
