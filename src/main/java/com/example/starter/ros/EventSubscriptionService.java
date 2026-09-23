package com.example.starter.ros;

import com.example.starter.ros.db.EventRepository;
import com.example.starter.ros.db.SubscriptionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 事件订阅与 ACK：同一 JVM 内断线重连只续传大于已确认游标的事件。
 */
@Service
public class EventSubscriptionService {

    private static final int MAX_LIMIT = 500;

    private final EventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;

    public EventSubscriptionService(EventRepository eventRepository,
                                    SubscriptionRepository subscriptionRepository) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    /** 订阅者当前 ACK 游标；无记录时为 0。 */
    public long ackCursor(String subscriberId) {
        Long cursor = subscriptionRepository.findAckCursor(subscriberId);
        return cursor == null ? 0L : cursor;
    }

    /** 拉取严格大于已确认游标的事件（升序）；游标为 ACK 值，因此重连不会收到已确认事件。 */
    public List<EventRecord> pull(String subscriberId, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return eventRepository.findAfter(ackCursor(subscriberId), bounded);
    }

    /** 确认游标；游标只允许单调向前推进。 */
    public void ack(String subscriberId, long cursor) {
        subscriptionRepository.saveAck(subscriberId, cursor);
    }
}
