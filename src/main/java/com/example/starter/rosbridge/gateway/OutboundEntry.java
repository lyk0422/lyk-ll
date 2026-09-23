package com.example.starter.rosbridge.gateway;

import com.example.starter.rosbridge.protocol.Protocol;

/**
 * 客户端出站队列中的条目。文本帧为普通响应；Topic 帧带共享订阅与游标，
 * 用于慢消费者丢弃与缺口合并；缺口帧可变范围，用于合并相邻丢弃。
 */
public sealed interface OutboundEntry {

    String render();

    /** 普通文本帧（connected、service_response、action_result 等）。 */
    record Text(String frame) implements OutboundEntry {
        @Override
        public String render() {
            return frame;
        }
    }

    /** 共享订阅消息帧；携带订阅元数据，供慢消费者丢弃时构造 RESUME_GAP。 */
    record Topic(long sharedId, long cursor, String topic, String type, String qosKey,
                 String frame) implements OutboundEntry {
        @Override
        public String render() {
            return frame;
        }
    }

    /** 丢失游标缺口帧；相邻丢弃会就地扩展范围。 */
    final class Gap implements OutboundEntry {
        private final String topic;
        private final String type;
        private final String qosKey;
        private long fromCursor;
        private long toCursor;

        public Gap(String topic, String type, String qosKey, long cursor) {
            this.topic = topic;
            this.type = type;
            this.qosKey = qosKey;
            this.fromCursor = cursor;
            this.toCursor = cursor;
        }

        public long fromCursor() {
            return fromCursor;
        }

        public long toCursor() {
            return toCursor;
        }

        public String topic() {
            return topic;
        }

        /** 若 cursor 与缺口任一端相邻则吸收并扩展范围，返回是否已合并。 */
        public boolean absorb(long cursor) {
            if (cursor == toCursor + 1) {
                toCursor = cursor;
                return true;
            }
            if (cursor == fromCursor - 1) {
                fromCursor = cursor;
                return true;
            }
            return false;
        }

        @Override
        public String render() {
            return Protocol.resumeGap(topic, type, qosKey, fromCursor, toCursor);
        }
    }
}
