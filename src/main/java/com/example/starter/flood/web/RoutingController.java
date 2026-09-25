package com.example.starter.flood.web;

import com.example.starter.flood.service.RoutingService;
import com.example.starter.flood.web.dto.RoutingRequest;
import com.example.starter.flood.web.dto.RoutingView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调洪演算入口：库容反查 + 双指标综合判定。
 */
@RestController
@RequestMapping("/api/routings")
public class RoutingController {

    private final RoutingService routingService;

    public RoutingController(RoutingService routingService) {
        this.routingService = routingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoutingView route(@Valid @RequestBody RoutingRequest request) {
        return routingService.route(request);
    }
}
