package com.example.starter.pest.api;

import com.example.starter.pest.OutboundService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出库登记接口。
 */
@RestController
@RequestMapping("/api/outbound-registrations")
public class OutboundController {

    private final OutboundService outboundService;

    public OutboundController(OutboundService outboundService) {
        this.outboundService = outboundService;
    }

    /**
     * 登记出库；处置中的库区被拒绝，其它库区不受影响。
     */
    @PostMapping
    public Results.OutboundResult register(@Valid @RequestBody Requests.OutboundRequest request) {
        return outboundService.register(request);
    }
}
