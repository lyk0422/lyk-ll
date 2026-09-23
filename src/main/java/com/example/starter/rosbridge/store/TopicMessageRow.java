package com.example.starter.rosbridge.store;

/** 留存的 Topic 消息行。 */
public record TopicMessageRow(long sharedId, long cursor, String payload, long createdAt) {
}
