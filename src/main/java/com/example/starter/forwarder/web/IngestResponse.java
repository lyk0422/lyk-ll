package com.example.starter.forwarder.web;

/**
 * 设备消息写入结果。
 *
 * @param accepted   是否接受进入待发缓存（false 表示按策略丢弃或容量不足被拒绝）
 * @param dedupKey   消息去重键
 * @param reason     未接受/去重时的原因说明
 * @param duplicated 是否为重复 dedupKey（已存在待发/在途记录）
 */
public record IngestResponse(boolean accepted, String dedupKey, String reason, boolean duplicated) {

    public static IngestResponse accepted(String dedupKey) {
        return new IngestResponse(true, dedupKey, null, false);
    }

    public static IngestResponse duplicate(String dedupKey) {
        return new IngestResponse(true, dedupKey, "dedupKey 已存在，忽略重复消息", true);
    }

    public static IngestResponse rejected(String dedupKey, String reason) {
        return new IngestResponse(false, dedupKey, reason, false);
    }
}
