package com.example.starter.web.dto;

/**
 * 投诉登记响应。duplicate=true 表示同监测站+频率+采样时刻已登记过；
 * eventCreated=true 表示本条恰为第 3 个不同监测站投诉并触发了干扰事件。
 */
public record ComplaintResponse(
        long complaintId,
        boolean duplicate,
        Long eventId,
        boolean eventCreated) {
}
