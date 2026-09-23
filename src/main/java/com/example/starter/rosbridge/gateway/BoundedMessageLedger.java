package com.example.starter.rosbridge.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.starter.rosbridge.config.RosBridgeProperties;
import com.example.starter.rosbridge.ros.SubscriptionKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 有界内存消息账本：每个共享订阅保留最近 N 条已分配游标的消息，供同一 JVM 内
 * 断线重连时按 ACK 游标补发。游标严格单调，队列按游标升序。
 */
@Component
public class BoundedMessageLedger implements MessageLedger {

    private final int capacity;
    private final Map<SubscriptionKey, Deque<LedgerEntry>> ledgers = new ConcurrentHashMap<>();

    public BoundedMessageLedger(RosBridgeProperties properties) {
        this.capacity = Math.max(16, properties.ledgerCapacity());
    }

    @Override
    public void append(SubscriptionKey key, long cursor, String type, JsonNode data) {
        Deque<LedgerEntry> deque = ledgers.computeIfAbsent(key, k -> new ArrayDeque<>(capacity));
        synchronized (deque) {
            deque.addLast(new LedgerEntry(cursor, type, data));
            while (deque.size() > capacity) {
                deque.removeFirst();
            }
        }
    }

    @Override
    public List<LedgerEntry> after(SubscriptionKey key, long afterCursor) {
        Deque<LedgerEntry> deque = ledgers.get(key);
        if (deque == null) {
            return List.of();
        }
        List<LedgerEntry> result = new ArrayList<>();
        synchronized (deque) {
            for (LedgerEntry entry : deque) {
                if (entry.cursor() > afterCursor) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    /** 释放某共享订阅的账本（上游订阅真正释放时调用）。 */
    public void remove(SubscriptionKey key) {
        ledgers.remove(key);
    }

    /** 清空全部账本（测试场景间使用）。 */
    public void clear() {
        ledgers.clear();
    }
}
