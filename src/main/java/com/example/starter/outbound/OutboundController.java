package com.example.starter.outbound;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出库登记接口。
 */
@RestController
@RequestMapping("/api")
public class OutboundController {

    private final OutboundService service;

    public OutboundController(OutboundService service) {
        this.service = service;
    }

    /**
     * 出库登记：POST /api/zones/{zoneCode}/outbounds
     */
    @PostMapping("/zones/{zoneCode}/outbounds")
    public OutboundDtos.OutboundResponse register(@PathVariable String zoneCode,
            @Valid @RequestBody OutboundDtos.OutboundRequest request) {
        return service.register(zoneCode, request);
    }
}
