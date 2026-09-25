package com.example.starter.ventilation.dto;

/**
 * 反馈受理结果。
 *
 * @param accepted   是否被受理并改变指令状态（归档后迟到反馈为 false）
 * @param late       是否为归档后迟到反馈（仅记迟到事件）
 * @param commandUid 对应指令编码
 * @param status     反馈处理后指令状态
 * @param detail     说明
 */
public record FeedbackResult(
        boolean accepted,
        boolean late,
        String commandUid,
        String status,
        String detail) {
}
