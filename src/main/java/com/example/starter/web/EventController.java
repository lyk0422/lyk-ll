package com.example.starter.web;

import java.util.List;

import com.example.starter.service.EventService;
import com.example.starter.web.dto.CloseEventRequest;
import com.example.starter.web.dto.EventView;
import com.example.starter.web.dto.InvestigateRequest;
import com.example.starter.web.dto.InvestigateResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 干扰事件处置入口：初查、结案与查询（含 24 小时督办标记）。
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping("/{eventId}/investigation")
    public InvestigateResponse investigate(@PathVariable long eventId,
                                           @Valid @RequestBody InvestigateRequest request) {
        return eventService.investigate(eventId, request);
    }

    @PostMapping("/{eventId}/close")
    public EventView close(@PathVariable long eventId, @Valid @RequestBody CloseEventRequest request) {
        return eventService.close(eventId, request);
    }

    @GetMapping
    public List<EventView> list() {
        return eventService.list();
    }

    @GetMapping("/{eventId}")
    public EventView get(@PathVariable long eventId) {
        return eventService.get(eventId);
    }
}
