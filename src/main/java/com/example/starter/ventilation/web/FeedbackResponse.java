package com.example.starter.ventilation.web;

/**
 * 设备到位反馈响应。
 *
 * @param replay     是否 requestId 幂等重放
 * @param accepted   反馈是否被采纳（迟到反馈不改变归档结论，accepted=false）
 * @param late       是否为归档后迟到事件
 * @param sessionId  关联会话 id（可能为空）
 * @param commandId  关联指令 id（可能为空）
 * @param status     反馈处理后的指令状态
 * @param note       说明
 */
public record FeedbackResponse(
        boolean replay,
        boolean accepted,
        boolean late,
        Long sessionId,
        Long commandId,
        String status,
        String note
) {
}
