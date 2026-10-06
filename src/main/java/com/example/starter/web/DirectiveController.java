package com.example.starter.web;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.DirectiveService;
import com.example.starter.web.dto.ConfirmExecutionRequest;
import com.example.starter.web.dto.DirectiveResponse;

/**
 * 限功率指令入口：执行确认（实际功率恰等限值合法、超过拒绝）。
 */
@RestController
@RequestMapping("/api/directives")
public class DirectiveController {

    private final DirectiveService directiveService;

    public DirectiveController(DirectiveService directiveService) {
        this.directiveService = directiveService;
    }

    @PostMapping("/{directiveId}/confirm-execution")
    public DirectiveResponse confirmExecution(@PathVariable long directiveId,
            @Valid @RequestBody ConfirmExecutionRequest request) {
        return directiveService.confirmExecution(
                directiveId, request.actualPowerWatts(), request.confirmedBy());
    }
}
