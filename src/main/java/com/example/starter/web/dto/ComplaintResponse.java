package com.example.starter.web.dto;

/**
 * 投诉登记响应。
 *
 * @param complaintId      投诉主键
 * @param idempotentReplay 是否为同 requestId 同参的幂等重放
 * @param naturalDuplicate 是否命中同监测站+频率+采样时刻的既有登记
 * @param eventTriggered   是否恰由本次登记触发干扰事件
 * @param eventId          触发的干扰事件主键；未触发为 null
 */
public record ComplaintResponse(long complaintId, boolean idempotentReplay, boolean naturalDuplicate,
                                boolean eventTriggered, Long eventId) {
}
