package com.example.starter.ros.service;

import com.example.starter.ros.domain.NodeEvent;
import com.example.starter.ros.repository.EventJdbcRepository;
import com.example.starter.ros.web.dto.EventBatchResponse;
import com.example.starter.ros.web.dto.EventView;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件订阅服务:按订阅方水位拉取递增 revision 的资源变更事件。
 * 水位在确认(ack)时单调前进,乱序或重复确认不会令水位回退。
 */
@Service
public class EventSubscriptionService {

    private final EventJdbcRepository eventRepository;

    public EventSubscriptionService(EventJdbcRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * 拉取订阅方水位之后的事件,事件严格按 revision 升序返回。
     */
    @Transactional(readOnly = true)
    public EventBatchResponse poll(String subscriberId, int limit) {
        long cursor = eventRepository.getCursor(subscriberId);
        List<NodeEvent> events = eventRepository.findAfter(cursor, normalizeLimit(limit));
        List<EventView> views = new ArrayList<>();
        for (NodeEvent event : events) {
            views.add(new EventView(event.revision(), event.nodeId(),
                    event.eventType().name(), event.payloadJson(), event.createdAt()));
        }
        return new EventBatchResponse(subscriberId, cursor, views);
    }

    /**
     * 确认消费到某 revision;水位只增不减,返回确认后的水位。
     */
    @Transactional
    public long acknowledge(String subscriberId, long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision 不能为负数");
        }
        return eventRepository.advanceCursor(subscriberId, revision);
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 500);
    }
}
