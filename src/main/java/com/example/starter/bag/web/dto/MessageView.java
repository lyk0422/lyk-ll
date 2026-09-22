package com.example.starter.bag.web.dto;

/**
 * 查询返回的消息视图（负载以 Base64 返回）。
 */
public record MessageView(
        long id,
        String topic,
        long timestampNs,
        long seq,
        String typeId,
        String payloadBase64,
        String payloadChecksum,
        String batchNo,
        long segmentId) {
}
