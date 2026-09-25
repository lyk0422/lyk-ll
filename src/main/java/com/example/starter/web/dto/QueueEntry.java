package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 派单队列条目：按重要等级、影响户数降序、接报先后复算排序。
 *
 * @param workOrderId    工单ID
 * @param feederId       馈线编号
 * @param importantLevel 重要用户等级
 * @param affectedUsers  影响用户数
 * @param outageStartAt  停电开始（首次接报）时间
 * @param status         工单状态
 */
public record QueueEntry(
        long workOrderId,
        String feederId,
        String importantLevel,
        int affectedUsers,
        LocalDateTime outageStartAt,
        String status) {
}
