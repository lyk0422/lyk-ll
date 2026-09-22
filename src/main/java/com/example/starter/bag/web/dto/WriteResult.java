package com.example.starter.bag.web.dto;

/**
 * 写入结果。
 *
 * @param messageId 消息主键；幂等命中时返回既有消息 id
 * @param segmentId 所属分段 id
 * @param segIndex  Topic 内分段序号
 * @param duplicate 是否为同批次同参的幂等重试（true 表示未重复写入）
 */
public record WriteResult(long messageId, long segmentId, int segIndex, boolean duplicate) {
}
