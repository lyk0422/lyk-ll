package com.example.starter.atm.web.dto;

/**
 * 打卡结果：checkins 为当前计划累计打卡人数（0/1/2），escortCompleted=true 表示双人打卡齐备。
 * late=true 表示封存终态后迟到打卡，仅登记迟到事件，未生效。
 */
public record CheckinResponse(
        String planNo,
        String guardNo,
        int seq,
        int checkins,
        boolean escortCompleted,
        String status,
        boolean late
) {
}
