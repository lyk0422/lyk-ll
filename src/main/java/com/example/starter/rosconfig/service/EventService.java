package com.example.starter.rosconfig.service;

import com.example.starter.rosconfig.repo.EventRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 配置事件流：单调游标、订阅者 ACK 与断线续传（同一 JVM 内）。
 *
 * <p>订阅者 ACK 后位置持久化；断线重连且不显式提供游标时，只续传严格大于已确认游标的事件，
 * 因而不会重复投递。</p>
 */
@Service
public class EventService {

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public record Event(long cursor, String type, String transactionId, String payload) {
    }

    /**
     * 拉取事件。
     *
     * @param subscriberId 订阅者标识
     * @param afterCursor  显式起始游标（严格大于）；为 null 时使用该订阅者已 ACK 的位置（断线续传）
     * @param limit        最多返回条数
     */
    public List<Event> poll(String subscriberId, Long afterCursor, int limit) {
        long startAfter = afterCursor != null ? afterCursor : eventRepository.getAckedCursor(subscriberId);
        int bounded = Math.min(Math.max(limit, 1), 1000);
        return eventRepository.findEventsAfter(startAfter, bounded).stream()
                .map(row -> new Event(row.cursor(), row.eventType(), row.transactionId(), row.payload()))
                .toList();
    }

    /** 订阅者确认已消费到的游标，保存位置（单调不回退）。 */
    public void ack(String subscriberId, long cursor) {
        eventRepository.saveAckedCursor(subscriberId, cursor);
    }

    /** 订阅者当前已确认游标，未确认过返回 0。 */
    public long ackedCursor(String subscriberId) {
        return eventRepository.getAckedCursor(subscriberId);
    }
}
