package com.example.starter.runway.dto;

/**
 * 巡检评估响应。
 *
 * @param assessmentId 评估记录ID
 * @param eventId      事件ID
 * @param passed       本次系数是否合格（>=0.35，恰0.35合格）
 * @param retest       是否修复后复测
 * @param eventStatus  当前事件状态（不合格时保持关闭，即持续关闭）
 */
public record AssessmentResponse(
        long assessmentId,
        long eventId,
        boolean passed,
        boolean retest,
        String eventStatus) {
}
