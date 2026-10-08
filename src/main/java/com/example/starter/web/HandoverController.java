package com.example.starter.web;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.HandoverService;
import com.example.starter.web.dto.HandoverResponse;
import com.example.starter.web.dto.Requests.HandoverApplyRequest;
import com.example.starter.web.dto.Requests.HandoverCompleteRequest;
import com.example.starter.web.dto.Requests.HandoverConfirmRequest;
import com.example.starter.web.dto.Requests.HandoverWithdrawRequest;

/**
 * 执行中引航交班 REST 入口：申请、双方签认、完成切换、撤回与查询。
 */
@RestController
@RequestMapping("/api/handovers")
public class HandoverController {

    private final HandoverService service;

    public HandoverController(HandoverService service) {
        this.service = service;
    }

    @PostMapping("/apply")
    public HandoverResponse apply(@Valid @RequestBody HandoverApplyRequest request) {
        return service.apply(request);
    }

    @PostMapping("/confirm")
    public HandoverResponse confirm(@Valid @RequestBody HandoverConfirmRequest request) {
        return service.confirm(request);
    }

    @PostMapping("/complete")
    public HandoverResponse complete(@Valid @RequestBody HandoverCompleteRequest request) {
        return service.complete(request);
    }

    @PostMapping("/withdraw")
    public HandoverResponse withdraw(@Valid @RequestBody HandoverWithdrawRequest request) {
        return service.withdraw(request);
    }

    @GetMapping("/{handoverId}")
    public HandoverResponse get(@PathVariable long handoverId) {
        return service.get(handoverId);
    }
}
