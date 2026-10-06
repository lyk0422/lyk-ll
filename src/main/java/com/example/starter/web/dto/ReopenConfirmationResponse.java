package com.example.starter.web.dto;

/**
 * 复开双岗确认响应。
 *
 * @param eventId              管制事件 id
 * @param status               事件状态
 * @param operationsConfirmed  运营岗是否已确认
 * @param maintenanceConfirmed 机务岗是否已确认
 * @param resumed              是否已复开（归档终态）
 */
public record ReopenConfirmationResponse(long eventId, String status, boolean operationsConfirmed,
                                         boolean maintenanceConfirmed, boolean resumed) {
}
