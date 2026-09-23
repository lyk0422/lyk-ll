package com.example.starter.forwarder.web;

/**
 * ACK 处理结果。
 *
 * @param matched    是否精确确认了一条 in-flight 消息
 * @param dedupKey   去重键
 * @param duplicated 是否为重复/无效 ACK（不改变任何计数）
 */
public record AckResponse(boolean matched, String dedupKey, boolean duplicated) {

    public static AckResponse matched(String dedupKey) {
        return new AckResponse(true, dedupKey, false);
    }

    public static AckResponse duplicate(String dedupKey) {
        return new AckResponse(false, dedupKey, true);
    }
}
