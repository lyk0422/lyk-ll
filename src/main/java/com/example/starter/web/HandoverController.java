package com.example.starter.web;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.HandoverService;
import com.example.starter.web.dto.HandoverResponse;
import com.example.starter.web.dto.Requests.HandoverAckRequest;
import com.example.starter.web.dto.Requests.HandoverApplyRequest;
import com.example.starter.web.dto.Requests.HandoverWithdrawRequest;

/**
 * 执行中引航交班 REST 入口：申请、双方签认（满足条件时原子切换）与撤回。
 */
@RestController
@RequestMapping("/api/pilot-plans/handovers")
public class HandoverController {

    private final HandoverService service;

    public HandoverController(HandoverService service) {
        this.service = service;
    }

    @PostMapping("/apply")
    public HandoverResponse apply(@Valid @RequestBody HandoverApplyRequest request) {
        return service.apply(request);
    }

    @PostMapping("/ack")
    public HandoverResponse ack(@Valid @RequestBody HandoverAckRequest request) {
        return service.ack(request);
    }

    @PostMapping("/withdraw")
    public HandoverResponse withdraw(@Valid @RequestBody HandoverWithdrawRequest request) {
        return service.withdraw(request);
    }
}
