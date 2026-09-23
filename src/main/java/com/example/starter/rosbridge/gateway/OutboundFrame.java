package com.example.starter.rosbridge.gateway;

/**
 * 待发往客户端的一帧。topic 帧携带游标用于慢消费者丢弃与 ACK 跟踪。
 */
public record OutboundFrame(String payload, String topic, long cursor, boolean topicFrame) {

    public static OutboundFrame control(String payload) {
        return new OutboundFrame(payload, null, 0, false);
    }

    public static OutboundFrame topic(String payload, String topic, long cursor) {
        return new OutboundFrame(payload, topic, cursor, true);
    }
}
