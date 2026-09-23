package com.example.starter.dto;

import com.example.starter.model.DeviceMessage;

/**
 * 设备消息入库结果。
 *
 * @param outcome ACCEPTED=已写入待发队列，DUPLICATE=dedupKey 已存在（幂等忽略），
 *                DROPPED=离线策略丢弃（未伪装成已转发）
 * @param message 关联消息；DROPPED 时也回填消息字段用于审计关联，DUPLICATE 时为已存在记录
 */
public record IngestResult(String outcome, DeviceMessage message) {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String DROPPED = "DROPPED";
}
