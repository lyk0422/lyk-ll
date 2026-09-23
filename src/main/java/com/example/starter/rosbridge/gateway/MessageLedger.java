package com.example.starter.rosbridge.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.starter.rosbridge.ros.SubscriptionKey;
import java.util.List;

/**
 * 共享订阅消息账本：保存每个共享订阅最近 N 条 (cursor, data)，
 * 用于同一 JVM 内断线重连且 ACK 之后的补发。线程安全。
 */
public interface MessageLedger {

    /** 追加一条已分配游标的消息。 */
    void append(SubscriptionKey key, long cursor, String type, JsonNode data);

    /** 取出游标严格大于 afterCursor 的可用消息（按游标升序）。 */
    List<LedgerEntry> after(SubscriptionKey key, long afterCursor);

    record LedgerEntry(long cursor, String type, JsonNode data) {
    }
}
