package com.example.starter.rosbridge.store;

/** 共享上游订阅行。 */
public record SharedSubscriptionRow(long id, String topic, String msgType, String qosKey,
                                    int refCount, boolean upstreamActive,
                                    long nextCursor, Long graceUntil, long createdAt) {
}
