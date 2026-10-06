package com.example.starter.web;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.EventService;
import com.example.starter.web.dto.CloseEventRequest;
import com.example.starter.web.dto.EscalationScanResponse;
import com.example.starter.web.dto.EventResponse;
import com.example.starter.web.dto.ReviewRequest;
import com.example.starter.web.dto.ReviewResponse;

/**
 * 干扰事件处置入口：初查、结案、督办扫描、详情查询。
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping("/{eventId}/review")
    public ReviewResponse review(@PathVariable long eventId,
            @Valid @RequestBody ReviewRequest request) {
        return eventService.review(eventId, request);
    }

    @PostMapping("/{eventId}/close")
    public EventResponse close(@PathVariable long eventId,
            @Valid @RequestBody CloseEventRequest request) {
        return eventService.close(eventId, request);
    }

    @PostMapping("/escalation-scan")
    public EscalationScanResponse escalationScan() {
        return eventService.escalationScan();
    }

    @GetMapping("/{eventId}")
    public EventResponse get(@PathVariable long eventId) {
        return eventService.get(eventId);
    }
}
