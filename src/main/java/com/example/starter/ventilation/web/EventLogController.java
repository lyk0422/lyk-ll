package com.example.starter.ventilation.web;

import com.example.starter.ventilation.domain.EventLogRow;
import com.example.starter.ventilation.repo.EventLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 事件流水查询：全部指令、反馈与状态迁移按自增 id 有序可查。
 */
@RestController
@RequestMapping("/api/ventilation/events")
public class EventLogController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final EventLogRepository events;

    public EventLogController(EventLogRepository events) {
        this.events = events;
    }

    /** 全量分页查询，?afterId=0&amp;limit=100。 */
    @GetMapping
    public EventLogView.Page page(@RequestParam(defaultValue = "0") long afterId,
                                  @RequestParam(defaultValue = "100") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<EventLogRow> rows = events.findPage(afterId, safeLimit + 1);
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = rows.subList(0, safeLimit);
        }
        List<EventLogView> views = rows.stream()
                .map(r -> new EventLogView(r.id(), r.eventType(), r.sessionId(), r.zoneCode(),
                        r.requestId(), r.detail(), r.eventTime()))
                .toList();
        return new EventLogView.Page(views, hasMore);
    }
}
