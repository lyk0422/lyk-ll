package com.example.starter.ros.service;

import com.example.starter.ros.dto.ParamEvent;
import com.example.starter.ros.repo.EventJdbcRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** 事件续传：以订阅者已确认游标为起点，同一 JVM 内断线重连只续传大于已确认游标的事件。 */
@Service
public class EventStreamServiceImpl implements EventStreamService {

    private final EventJdbcRepository eventRepository;

    public EventStreamServiceImpl(EventJdbcRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public List<ParamEvent> pull(String subscriberId, int limit) {
        long acked = eventRepository.getAck(subscriberId);
        return eventRepository.findAfter(acked, limit);
    }

    @Override
    public void ack(String subscriberId, long cursor) {
        if (cursor < 0) {
            throw new IllegalArgumentException("游标不能为负");
        }
        eventRepository.ack(subscriberId, cursor);
    }

    @Override
    public long ackedCursor(String subscriberId) {
        return eventRepository.getAck(subscriberId);
    }
}
