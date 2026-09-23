package com.example.starter.rosbridge.store;

/** 客户端行。 */
public record ClientRow(String clientId, boolean connected, int rateLimitPerSec,
                        int queueCap, long connectedAt, Long disconnectedAt) {
}
