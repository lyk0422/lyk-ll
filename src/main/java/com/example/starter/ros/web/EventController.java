package com.example.starter.ros.web;

import com.example.starter.ros.dto.ParamEvent;
import com.example.starter.ros.service.EventStreamService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 单调游标事件流：拉取、ACK，重连续传大于已确认游标的事件。 */
@RestController
@RequestMapping("/api/ros/events")
@Validated
public class EventController {

    private final EventStreamService eventStreamService;

    public EventController(EventStreamService eventStreamService) {
        this.eventStreamService = eventStreamService;
    }

    @GetMapping
    public List<ParamEvent> pull(@RequestParam @NotBlank String subscriberId,
                                 @RequestParam(defaultValue = "100") int limit) {
        return eventStreamService.pull(subscriberId, Math.min(Math.max(limit, 1), 1000));
    }

    @PostMapping("/ack")
    public Map<String, Object> ack(@RequestBody AckRequest request) {
        eventStreamService.ack(request.subscriberId(), request.cursor());
        return Map.of("subscriberId", request.subscriberId(), "ackedCursor", request.cursor());
    }

    /** ACK 请求体。 */
    public record AckRequest(@NotBlank String subscriberId, @PositiveOrZero long cursor) {
    }
}
