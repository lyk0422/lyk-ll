package com.example.starter.runway.dto;

/**
 * 巡检开始响应。
 *
 * @param inspectionId         巡检记录ID
 * @param eventId              事件ID
 * @param late                 是否迟延（恰满15分钟未开始即迟延）
 * @param escalated            事件是否已升级
 * @param recordedAfterArchive 是否归档后迟到登记（仅登记，不改变状态）
 * @param eventStatus          当前事件状态
 */
public record InspectionResponse(
        long inspectionId,
        long eventId,
        boolean late,
        boolean escalated,
        boolean recordedAfterArchive,
        String eventStatus) {
}
