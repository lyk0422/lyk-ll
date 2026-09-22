package com.example.starter.bag.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 单条消息写入请求。负载以 Base64 传输，负载校验值由服务端计算。
 *
 * @param topic       Topic 名称
 * @param timestampNs 纳秒时间戳（UTC 纪元纳秒）
 * @param seq         Topic 内序列号，必须大于该 Topic 已提交的最大序列号
 * @param typeId      消息类型标识
 * @param payloadBase64 Base64 编码的消息负载，可为空
 * @param batchNo     写入批次号，同批次同参重试幂等
 */
public record WriteMessageRequest(
        @NotBlank String topic,
        @NotNull Long timestampNs,
        @PositiveOrZero long seq,
        @NotBlank String typeId,
        String payloadBase64,
        @NotBlank String batchNo) {
}
