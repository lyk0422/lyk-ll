package com.example.starter.rosbridge.store;

/** 客户端订阅行。 */
public record ClientSubscriptionRow(String clientId, long sharedId, long lastAckCursor,
                                    String status, long subscribedAt, Long graceUntil) {

    public static final String ACTIVE = "ACTIVE";
    public static final String GRACING = "GRACING";
}
