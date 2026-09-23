package com.example.starter.dto;

/**
 * 消息写入结果。
 *
 * @param outcome ACCEPTED（已落库待发）/ DROPPED（策略丢弃）/ REJECTED（容量不足拒绝）/ DEDUP（重复去重键跳过）
 * @param reason  原因说明，审计可查
 * @param messageId 落库消息主键；未落库为 null
 */
public record IngestResponse(String outcome, String reason, Long messageId) {
}
