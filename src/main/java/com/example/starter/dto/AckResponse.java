package com.example.starter.dto;

/**
 * ACK 处理结果。
 *
 * @param outcome DELIVERED（本次确认完成）/ IGNORED（重复或无对应消息，未改变计数）
 */
public record AckResponse(String outcome, String dedupKey) {
}
